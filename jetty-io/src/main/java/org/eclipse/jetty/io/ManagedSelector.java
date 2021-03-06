//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.EatWhatYouKill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>{@link ManagedSelector} wraps a {@link Selector} simplifying non-blocking operations on channels.</p>
 * <p>{@link ManagedSelector} runs the select loop, which waits on {@link Selector#select()} until events
 * happen for registered channels. When events happen, it notifies the {@link EndPoint} associated
 * with the channel.</p>
 */
public class ManagedSelector extends ContainerLifeCycle implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ManagedSelector.class);
    private static final boolean FORCE_SELECT_NOW;

    static
    {
        String property = System.getProperty("org.eclipse.jetty.io.forceSelectNow");
        if (property != null)
        {
            FORCE_SELECT_NOW = Boolean.parseBoolean(property);
        }
        else
        {
            property = System.getProperty("os.name");
            FORCE_SELECT_NOW = property != null && property.toLowerCase(Locale.ENGLISH).contains("windows");
        }
    }

    private final AtomicBoolean _started = new AtomicBoolean(false);
    private boolean _selecting = false;
    private final SelectorManager _selectorManager;
    private final int _id;
    private final ExecutionStrategy _strategy;
    private Selector _selector;
    private Deque<SelectorUpdate> _updates = new ArrayDeque<>();
    private Deque<SelectorUpdate> _updateable = new ArrayDeque<>();

    public ManagedSelector(SelectorManager selectorManager, int id)
    {
        _selectorManager = selectorManager;
        _id = id;
        SelectorProducer producer = new SelectorProducer();
        Executor executor = selectorManager.getExecutor();
        _strategy = new EatWhatYouKill(producer, executor);
        addBean(_strategy, true);
    }

    public Selector getSelector()
    {
        return _selector;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        _selector = _selectorManager.newSelector();

        // The producer used by the strategies will never
        // be idle (either produces a task or blocks).

        // The normal strategy obtains the produced task, schedules
        // a new thread to produce more, runs the task and then exits.
        _selectorManager.execute(_strategy::produce);

        // Set started only if we really are started
        Start start = new Start();
        submit(start);
        start._started.await();
    }

    protected void onSelectFailed(Throwable cause)
    {
        // override to change behavior
    }

    public int size()
    {
        Selector s = _selector;
        if (s == null)
            return 0;
        Set<SelectionKey> keys = s.keys();
        if (keys == null)
            return 0;
        return keys.size();
    }

    @Override
    protected void doStop() throws Exception
    {
        // doStop might be called for a failed managedSelector,
        // We do not want to wait twice, so we only stop once for each start
        if (_started.compareAndSet(true, false) && _selector != null)
        {
            // Close connections, but only wait a single selector cycle for it to take effect
            CloseConnections closeConnections = new CloseConnections();
            submit(closeConnections);
            closeConnections._complete.await();

            // Wait for any remaining endpoints to be closed and the selector to be stopped
            StopSelector stopSelector = new StopSelector();
            submit(stopSelector);
            stopSelector._stopped.await();
        }

        super.doStop();
    }

    /**
     * Submit an {@link SelectorUpdate} to be acted on between calls to {@link Selector#select()}
     *
     * @param update The selector update to apply at next wakeup
     */
    public void submit(SelectorUpdate update)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Queued change {} on {}", update, this);

        Selector selector = null;
        synchronized (ManagedSelector.this)
        {
            _updates.offer(update);

            if (_selecting)
            {
                selector = _selector;
                // To avoid the extra select wakeup.
                _selecting = false;
            }
        }

        if (selector != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Wakeup on submit {}", this);
            selector.wakeup();
        }
    }

    private void wakeup()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Wakeup {}", this);

        Selector selector = null;
        synchronized (ManagedSelector.this)
        {
            if (_selecting)
            {
                selector = _selector;
                _selecting = false;
            }
        }

        if (selector != null)
            selector.wakeup();
    }

    private void execute(Runnable task)
    {
        try
        {
            _selectorManager.execute(task);
        }
        catch (RejectedExecutionException x)
        {
            if (task instanceof Closeable)
                IO.close((Closeable)task);
        }
    }

    private void processConnect(SelectionKey key, final Connect connect)
    {
        SelectableChannel channel = key.channel();
        try
        {
            key.attach(connect.attachment);
            boolean connected = _selectorManager.doFinishConnect(channel);
            if (LOG.isDebugEnabled())
                LOG.debug("Connected {} {}", connected, channel);
            if (connected)
            {
                if (connect.timeout.cancel())
                {
                    key.interestOps(0);
                    execute(new CreateEndPoint(connect, key));
                }
                else
                {
                    throw new SocketTimeoutException("Concurrent Connect Timeout");
                }
            }
            else
            {
                throw new ConnectException();
            }
        }
        catch (Throwable x)
        {
            connect.failed(x);
        }
    }

    protected void endPointOpened(EndPoint endPoint)
    {
        _selectorManager.endPointOpened(endPoint);
    }

    protected void endPointClosed(EndPoint endPoint)
    {
        _selectorManager.endPointClosed(endPoint);
    }

    private void createEndPoint(SelectableChannel channel, SelectionKey selectionKey) throws IOException
    {
        EndPoint endPoint = _selectorManager.newEndPoint(channel, this, selectionKey);
        Object context = selectionKey.attachment();
        Connection connection = _selectorManager.newConnection(channel, endPoint, context);
        endPoint.setConnection(connection);
        selectionKey.attach(endPoint);
        endPoint.onOpen();
        endPointOpened(endPoint);
        _selectorManager.connectionOpened(connection, context);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", endPoint);
    }

    public void destroyEndPoint(final EndPoint endPoint, Throwable cause)
    {
        // Waking up the selector is necessary to clean the
        // cancelled-key set and tell the TCP stack that the
        // socket is closed (so that senders receive RST).
        wakeup();
        execute(new DestroyEndPoint(endPoint, cause));
    }

    private int getActionSize()
    {
        synchronized (ManagedSelector.this)
        {
            return _updates.size();
        }
    }

    static int safeReadyOps(SelectionKey selectionKey)
    {
        try
        {
            return selectionKey.readyOps();
        }
        catch (Throwable x)
        {
            LOG.trace("IGNORED", x);
            return -1;
        }
    }

    static int safeInterestOps(SelectionKey selectionKey)
    {
        try
        {
            return selectionKey.interestOps();
        }
        catch (Throwable x)
        {
            LOG.trace("IGNORED", x);
            return -1;
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<String> keys;
        List<SelectorUpdate> updates;
        Selector selector = _selector;
        if (selector != null && selector.isOpen())
        {
            final DumpKeys dump = new DumpKeys();
            final String updatesAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now());
            synchronized (ManagedSelector.this)
            {
                updates = new ArrayList<>(_updates);
                _updates.addFirst(dump);
                _selecting = false;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("wakeup on dump {}", this);
            selector.wakeup();
            keys = dump.get(5, TimeUnit.SECONDS);
            String keysAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now());
            if (keys == null)
                keys = Collections.singletonList("No dump keys retrieved");

            dumpObjects(out, indent,
                new DumpableCollection("updates @ " + updatesAt, updates),
                new DumpableCollection("keys @ " + keysAt, keys));
        }
        else
        {
            dumpObjects(out, indent);
        }
    }

    @Override
    public String toString()
    {
        Selector selector = _selector;
        return String.format("%s id=%s keys=%d selected=%d updates=%d",
            super.toString(),
            _id,
            selector != null && selector.isOpen() ? selector.keys().size() : -1,
            selector != null && selector.isOpen() ? selector.selectedKeys().size() : -1,
            getActionSize());
    }

    /**
     * A {@link Selectable} is an {@link EndPoint} that wish to be
     * notified of non-blocking events by the {@link ManagedSelector}.
     */
    public interface Selectable
    {
        /**
         * Callback method invoked when a read or write events has been
         * detected by the {@link ManagedSelector} for this endpoint.
         *
         * @return a job that may block or null
         */
        Runnable onSelected();

        /**
         * Callback method invoked when all the keys selected by the
         * {@link ManagedSelector} for this endpoint have been processed.
         */
        void updateKey();
    }

    private class SelectorProducer implements ExecutionStrategy.Producer
    {
        private Set<SelectionKey> _keys = Collections.emptySet();
        private Iterator<SelectionKey> _cursor = Collections.emptyIterator();

        @Override
        public Runnable produce()
        {
            while (true)
            {
                Runnable task = processSelected();
                if (task != null)
                    return task;

                processUpdates();

                updateKeys();

                if (!select())
                    return null;
            }
        }

        private void processUpdates()
        {
            synchronized (ManagedSelector.this)
            {
                Deque<SelectorUpdate> updates = _updates;
                _updates = _updateable;
                _updateable = updates;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("updateable {}", _updateable.size());

            for (SelectorUpdate update : _updateable)
            {
                if (_selector == null)
                    break;
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("update {}", update);
                    update.update(_selector);
                }
                catch (Throwable th)
                {
                    LOG.warn("Cannot update selector {}", _selector, th);
                }
            }
            _updateable.clear();

            Selector selector;
            int updates;
            synchronized (ManagedSelector.this)
            {
                updates = _updates.size();
                _selecting = updates == 0;
                selector = _selecting ? null : _selector;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("updates {}", updates);

            if (selector != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("wakeup on updates {}", this);
                selector.wakeup();
            }
        }

        private boolean select()
        {
            try
            {
                Selector selector = _selector;
                if (selector != null && selector.isOpen())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} waiting with {} keys", selector, selector.keys().size());
                    int selected = selector.select();
                    if (selected == 0)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Selector {} woken with none selected", selector);

                        if (Thread.interrupted() && !isRunning())
                            throw new ClosedSelectorException();

                        if (FORCE_SELECT_NOW)
                            selected = selector.selectNow();
                    }
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} woken up from select, {}/{}/{} selected", selector, selected, selector.selectedKeys().size(), selector.keys().size());

                    int updates;
                    synchronized (ManagedSelector.this)
                    {
                        // finished selecting
                        _selecting = false;
                        updates = _updates.size();
                    }

                    _keys = selector.selectedKeys();
                    _cursor = _keys.isEmpty() ? Collections.emptyIterator() : _keys.iterator();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} processing {} keys, {} updates", selector, _keys.size(), updates);

                    return true;
                }
            }
            catch (Throwable x)
            {
                IO.close(_selector);
                _selector = null;

                if (isRunning())
                {
                    LOG.warn("Fatal select() failure", x);
                    onSelectFailed(x);
                }
                else
                {
                    LOG.warn(x.toString());
                    LOG.debug("select() failure", x);
                }
            }
            return false;
        }

        private Runnable processSelected()
        {
            while (_cursor.hasNext())
            {
                SelectionKey key = _cursor.next();
                if (key.isValid())
                {
                    Object attachment = key.attachment();
                    if (LOG.isDebugEnabled())
                        LOG.debug("selected {} {} {} ", safeReadyOps(key), key, attachment);
                    try
                    {
                        if (attachment instanceof Selectable)
                        {
                            // Try to produce a task
                            Runnable task = ((Selectable)attachment).onSelected();
                            if (task != null)
                                return task;
                        }
                        else if (key.isConnectable())
                        {
                            processConnect(key, (Connect)attachment);
                        }
                        else
                        {
                            throw new IllegalStateException("key=" + key + ", att=" + attachment + ", iOps=" + safeInterestOps(key) + ", rOps=" + safeReadyOps(key));
                        }
                    }
                    catch (CancelledKeyException x)
                    {
                        LOG.debug("Ignoring cancelled key for channel {}", key.channel());
                        if (attachment instanceof EndPoint)
                            IO.close((EndPoint)attachment);
                    }
                    catch (Throwable x)
                    {
                        LOG.warn("Could not process key for channel " + key.channel(), x);
                        if (attachment instanceof EndPoint)
                            IO.close((EndPoint)attachment);
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector loop ignoring invalid key for channel {}", key.channel());
                    Object attachment = key.attachment();
                    if (attachment instanceof EndPoint)
                        IO.close((EndPoint)attachment);
                }
            }
            return null;
        }

        private void updateKeys()
        {
            // Do update keys for only previously selected keys.
            // This will update only those keys whose selection did not cause an
            // updateKeys update to be submitted.
            for (SelectionKey key : _keys)
            {
                Object attachment = key.attachment();
                if (attachment instanceof Selectable)
                    ((Selectable)attachment).updateKey();
            }
            _keys.clear();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", getClass().getSimpleName(), hashCode());
        }
    }

    /**
     * A selector update to be done when the selector has been woken.
     */
    public interface SelectorUpdate
    {
        void update(Selector selector);
    }

    private class Start implements SelectorUpdate
    {
        private final CountDownLatch _started = new CountDownLatch(1);

        @Override
        public void update(Selector selector)
        {
            ManagedSelector.this._started.set(true);
            _started.countDown();
        }
    }

    private static class DumpKeys implements SelectorUpdate
    {
        private CountDownLatch latch = new CountDownLatch(1);
        private List<String> keys;

        @Override
        public void update(Selector selector)
        {
            Set<SelectionKey> selectorKeys = selector.keys();
            List<String> list = new ArrayList<>(selectorKeys.size());
            for (SelectionKey key : selectorKeys)
            {
                if (key != null)
                    list.add(String.format("SelectionKey@%x{i=%d}->%s", key.hashCode(), safeInterestOps(key), key.attachment()));
            }
            keys = list;
            latch.countDown();
        }

        public List<String> get(long timeout, TimeUnit unit)
        {
            try
            {
                latch.await(timeout, unit);
            }
            catch (InterruptedException x)
            {
                LOG.trace("IGNORED", x);
            }
            return keys;
        }
    }

    class Acceptor implements SelectorUpdate, Selectable, Closeable
    {
        private final SelectableChannel _channel;
        private SelectionKey _key;

        public Acceptor(SelectableChannel channel)
        {
            this._channel = channel;
        }

        @Override
        public void update(Selector selector)
        {
            try
            {
                if (_key == null)
                {
                    _key = _channel.register(selector, SelectionKey.OP_ACCEPT, this);
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("{} acceptor={}", this, _key);
            }
            catch (Throwable x)
            {
                IO.close(_channel);
                LOG.warn("Unable to register OP_ACCEPT on selector", x);
            }
        }

        @Override
        public Runnable onSelected()
        {
            SelectableChannel server = _key.channel();
            SelectableChannel channel = null;
            try
            {
                while (true)
                {
                    channel = _selectorManager.doAccept(server);
                    if (channel == null)
                        break;
                    _selectorManager.accepted(channel);
                }
            }
            catch (Throwable x)
            {
                IO.close(channel);
                LOG.warn("Accept failed for channel " + channel, x);
            }

            return null;
        }

        @Override
        public void updateKey()
        {
        }

        @Override
        public void close() throws IOException
        {
            SelectionKey key = _key;
            _key = null;
            if (key != null && key.isValid())
                key.cancel();
        }
    }

    class Accept implements SelectorUpdate, Runnable, Closeable
    {
        private final SelectableChannel channel;
        private final Object attachment;
        private SelectionKey key;

        Accept(SelectableChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
            _selectorManager.onAccepting(channel);
        }

        @Override
        public void close()
        {
            LOG.debug("closed accept of {}", channel);
            IO.close(channel);
        }

        @Override
        public void update(Selector selector)
        {
            try
            {
                key = channel.register(selector, 0, attachment);
                execute(this);
            }
            catch (Throwable x)
            {
                IO.close(channel);
                _selectorManager.onAcceptFailed(channel, x);
                LOG.debug("Unable to register update for accept", x);
            }
        }

        @Override
        public void run()
        {
            try
            {
                createEndPoint(channel, key);
                _selectorManager.onAccepted(channel);
            }
            catch (Throwable x)
            {
                LOG.debug("Unable to accept", x);
                failed(x);
            }
        }

        protected void failed(Throwable failure)
        {
            IO.close(channel);
            LOG.warn("ManagedSelector#Accept failure : {}", Objects.toString(failure));
            LOG.debug("ManagedSelector#Accept failure", failure);
            _selectorManager.onAcceptFailed(channel, failure);
        }
    }

    class Connect implements SelectorUpdate, Runnable
    {
        private final AtomicBoolean failed = new AtomicBoolean();
        private final SelectableChannel channel;
        private final Object attachment;
        private final Scheduler.Task timeout;

        Connect(SelectableChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
            this.timeout = ManagedSelector.this._selectorManager.getScheduler().schedule(this, ManagedSelector.this._selectorManager.getConnectTimeout(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void update(Selector selector)
        {
            try
            {
                channel.register(selector, SelectionKey.OP_CONNECT, this);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        @Override
        public void run()
        {
            if (_selectorManager.isConnectionPending(channel))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Channel {} timed out while connecting, closing it", channel);
                failed(new SocketTimeoutException("Connect Timeout"));
            }
        }

        public void failed(Throwable failure)
        {
            if (failed.compareAndSet(false, true))
            {
                timeout.cancel();
                IO.close(channel);
                ManagedSelector.this._selectorManager.connectionFailed(channel, failure, attachment);
            }
        }

        @Override
        public String toString()
        {
            return String.format("Connect@%x{%s,%s}", hashCode(), channel, attachment);
        }
    }

    private class CloseConnections implements SelectorUpdate
    {
        final Set<Closeable> _closed;
        final CountDownLatch _noEndPoints = new CountDownLatch(1);
        final CountDownLatch _complete = new CountDownLatch(1);

        public CloseConnections()
        {
            this(null);
        }

        public CloseConnections(Set<Closeable> closed)
        {
            _closed = closed;
        }

        @Override
        public void update(Selector selector)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Closing {} connections on {}", selector.keys().size(), ManagedSelector.this);
            boolean zero = true;
            for (SelectionKey key : selector.keys())
            {
                if (key != null && key.isValid())
                {
                    Closeable closeable = null;
                    Object attachment = key.attachment();
                    if (attachment instanceof EndPoint)
                    {
                        EndPoint endp = (EndPoint)attachment;
                        if (!endp.isOutputShutdown())
                            zero = false;
                        Connection connection = endp.getConnection();
                        if (connection != null)
                            closeable = connection;
                        else
                            closeable = endp;
                    }

                    if (closeable != null)
                    {
                        if (_closed == null)
                        {
                            IO.close(closeable);
                        }
                        else if (!_closed.contains(closeable))
                        {
                            _closed.add(closeable);
                            IO.close(closeable);
                        }
                    }
                }
            }

            if (zero)
                _noEndPoints.countDown();
            _complete.countDown();
        }
    }

    private class StopSelector implements SelectorUpdate
    {
        CountDownLatch _stopped = new CountDownLatch(1);

        @Override
        public void update(Selector selector)
        {
            for (SelectionKey key : selector.keys())
            {
                if (key != null && key.isValid())
                {
                    Object attachment = key.attachment();
                    if (attachment instanceof EndPoint)
                        IO.close((EndPoint)attachment);
                }
            }

            _selector = null;
            IO.close(selector);
            _stopped.countDown();
        }
    }

    private final class CreateEndPoint implements Runnable
    {
        private final Connect _connect;
        private final SelectionKey _key;

        private CreateEndPoint(Connect connect, SelectionKey key)
        {
            _connect = connect;
            _key = key;
        }

        @Override
        public void run()
        {
            try
            {
                createEndPoint(_connect.channel, _key);
            }
            catch (Throwable failure)
            {
                IO.close(_connect.channel);
                LOG.warn("ManagedSelector#CreateEndpoint failure : {}", Objects.toString(failure));
                LOG.debug("ManagedSelector#CreateEndpoint failure", failure);
                _connect.failed(failure);
            }
        }

        @Override
        public String toString()
        {
            return String.format("CreateEndPoint@%x{%s,%s}", hashCode(), _connect, _key);
        }
    }

    private class DestroyEndPoint implements Runnable, Closeable
    {
        private final EndPoint endPoint;
        private final Throwable cause;

        public DestroyEndPoint(EndPoint endPoint, Throwable cause)
        {
            this.endPoint = endPoint;
            this.cause = cause;
        }

        @Override
        public void run()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Destroyed {}", endPoint);
            Connection connection = endPoint.getConnection();
            if (connection != null)
                _selectorManager.connectionClosed(connection, cause);
            ManagedSelector.this.endPointClosed(endPoint);
        }

        @Override
        public void close()
        {
            run();
        }
    }
}
