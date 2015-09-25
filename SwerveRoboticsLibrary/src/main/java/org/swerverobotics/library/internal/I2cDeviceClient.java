package org.swerverobotics.library.internal;

import android.util.Log;

import com.qualcomm.robotcore.hardware.*;
import com.qualcomm.robotcore.util.*;

import static junit.framework.Assert.*;
import org.swerverobotics.library.*;
import org.swerverobotics.library.exceptions.*;
import org.swerverobotics.library.interfaces.*;
import java.util.*;
import java.util.concurrent.locks.*;

/**
 * I2cDeviceClient is a utility class that makes it easy to read or write data to 
 * an instance of I2cDevice.
 */
public final class I2cDeviceClient implements II2cDeviceClient
    {
    //----------------------------------------------------------------------------------------------
    // State
    //----------------------------------------------------------------------------------------------

    /**
     * The I2cDevice of which are are a client.
     */
    public final II2cDevice     i2cDevice;                  // the device we are talking to

    private final Callback      callback;                   // the callback object on which we actually receive callbacks
    private       Thread        callbackThread;             // the thread on which we observe our callbacks to be made
    private       int           hardwareCycleCount;         // number of callbacks that we've received
    private       boolean       loggingEnabled;             // whether we are to log to Logcat or not
    private       String        loggingTag;                 // what we annotate our logging with
    private final ElapsedTime   timeSinceLastHeartbeat;     // keeps track of our need for doing heartbeats
    private       int           msHeartbeatInterval;        // time between heartbeats; zero is none necessary
    private       boolean       heartBeatUsingRead;         // true if we are to read for heartbeats, false if we are to write
    
    private final byte[]        readCache;                  // the buffer into which reads are retrieved
    private final byte[]        writeCache;                 // the buffer that we write from 
    private static final int    dibCacheOverhead = 4;       // this many bytes at start of writeCache are system overhead
    private static final int    ibActionFlag = 31;          // index of the action flag in our write cache
    private final Lock          readCacheLock;              // lock we must hold to look at readCache
    private final Lock          writeCacheLock;             // lock we must old to look at writeCache

    private final Object        concurrentClientLock = new Object(); // the lock we use to serialize against concurrent clients of us. Can't acquire this AFTER the callback lock.
    private final Object        callbackLock         = new Object(); // the lock we use to synchronize with our callback.

    private ReadWindow          readWindow;                 // the set of registers to look at when we are in read mode. May be null, indicating no read needed
    private ReadWindow          readWindowActuallyRead;     // the read window that was really read. readWindow will be a (possibly non-proper) subset of this
    private ReadWindow          readWindowSentToController; // the read window we last issued to the controller module. May disappear before read() returns
    private boolean             readWindowChanged;          // whether regWindow has changed since the hw cycle loop last took note
    private long                nanoTimeReadCacheValid;     // the time on the System.nanoTime() clock at which the read cache was last set as valid
    private READ_CACHE_STATUS   readCacheStatus;            // what we know about the contents of readCache
    private WRITE_CACHE_STATUS  writeCacheStatus;           // what we know about the (payload) contents of writeCache
    private MODE_CACHE_STATUS   modeCacheStatus;            // what we know about the first four bytes of writeCache (mostly a debugging aid)
    private int                 iregWriteFirst;             // when writeCacheStatus is DIRTY, this is where we want to write
    private int                 cregWrite;

    /** Keeps track of what we know about about the state of 'readCache' */
    private enum READ_CACHE_STATUS
        {
        IDLE,                 // the read cache is quiescent; it doesn't contain valid data
        SWITCHINGTOREADMODE,  // a request to switch to read mode has been made
        QUEUED,               // an I2C read has been queued, but we've not yet seen valid data
        QUEUE_COMPLETED,      // a transient state only ever seen within the callback
        VALID_ONLYONCE,       // read cache data has valid data but can only be read once
        VALID_QUEUED;         // read cache has valid data AND a read has been queued

        boolean isValid()
            {
            return this==VALID_QUEUED || this==VALID_ONLYONCE;
            }
        boolean isQueued()
            {
            return this==QUEUED || this==VALID_QUEUED;
            }
        }

    /** Keeps track about what we know about the state of 'writeCache' */
    private enum WRITE_CACHE_STATUS
        {
        IDLE,               // write cache is quiescent
        DIRTY,              // write cache has changed bits that need to be pushed to module
        QUEUED,             // write cache is currently being written to module, not yet returned
        }

    /** Keeps track about what we know about the state of the first four bytes of the
     * write cache, which are used for requesting a mode switch.
     */
    private enum MODE_CACHE_STATUS
        {
        IDLE,               // mode byte are quiesent
        DIRTY,              // mode bytes have changed, and need to be pushed to the module
        QUEUED,             // mode bytes have been queued to the module, but not yet returned.
        }
    
    //----------------------------------------------------------------------------------------------
    // Construction
    //----------------------------------------------------------------------------------------------

    /**
     * Instantiate an I2cDeviceClient instance in the indicated device with the indicated
     * initial window of registers being read.
     *
     * @param i2cDevice             the device we are to be a client of
     * @param i2cAddr8Bit           its 8 bit i2cAddress
     * @param initialReadWindow     initial reg window to use, may be null
     */
    public I2cDeviceClient(II2cDevice i2cDevice, int i2cAddr8Bit, ReadWindow initialReadWindow)
        {
        this(i2cDevice, i2cAddr8Bit, initialReadWindow, true);
        }

    /**
     * Instantiate an I2cDeviceClient instance in the indicated device with the indicated
     * initial window of registers being read.
     *
     * @param i2cDevice             the device we are to be a client of
     * @param i2cAddr8Bit           its 8 bit i2cAddress
     * @param initialReadWindow initial reg window to use, may be null
     * @param autoClose             if true, the device client will automatically close when the
     *                              associated SynchronousOpMode stops
     */
    public I2cDeviceClient(II2cDevice i2cDevice, int i2cAddr8Bit, ReadWindow initialReadWindow, boolean autoClose)
        {
        this.i2cDevice              = i2cDevice;
        this.callback               = new Callback();
        this.callbackThread         = null;
        this.hardwareCycleCount     = 0;
        this.loggingEnabled         = false;
        this.loggingTag             = String.format("I2cDeviceClient(%s)", i2cDevice.getDeviceName());;
        this.timeSinceLastHeartbeat = new ElapsedTime();
        this.timeSinceLastHeartbeat.reset();
        this.msHeartbeatInterval    = 0;
        this.heartBeatUsingRead     = true;

        this.readCache      = this.i2cDevice.getI2cReadCache();
        this.readCacheLock  = this.i2cDevice.getI2cReadCacheLock();
        this.writeCache     = this.i2cDevice.getI2cWriteCache();
        this.writeCacheLock = this.i2cDevice.getI2cWriteCacheLock();
        
        this.readWindow                 = initialReadWindow;
        this.readWindowActuallyRead     = null;
        this.readWindowSentToController = null;
        this.readWindowChanged          = false;

        this.nanoTimeReadCacheValid = 0;
        this.readCacheStatus  = READ_CACHE_STATUS.IDLE;
        this.writeCacheStatus = WRITE_CACHE_STATUS.IDLE;
        this.modeCacheStatus  = MODE_CACHE_STATUS.IDLE;
        
        this.i2cDevice.setI2cAddr(i2cAddr8Bit);

        if (autoClose)
            {
            IStopActionRegistrar registrar = SynchronousOpMode.getStopActionRegistrar();
            if (registrar != null)
                {
                registrar.registerActionOnStop(new IAction()
                {
                @Override public void doAction()
                    {
                    I2cDeviceClient.this.close();
                    }
                });
                }
            }

        this.i2cDevice.registerForI2cPortReadyCallback(this.callback);
        }

    //----------------------------------------------------------------------------------------------
    // HardwareDevice
    //----------------------------------------------------------------------------------------------
    
    public String getDeviceName()
        {
        return this.i2cDevice.getDeviceName();  
        }
    
    public String getConnectionInfo()
        {
        return this.i2cDevice.getConnectionInfo();
        }
    
    public int getVersion()
        {
        return this.i2cDevice.getVersion();
        }

    public void close()
    // NB: this HardwareDevice method is shared with I2cDevice.close()
        {
        this.i2cDevice.deregisterForPortReadyCallback();
        this.i2cDevice.close();
        }

    //----------------------------------------------------------------------------------------------
    // Operations
    //----------------------------------------------------------------------------------------------

    /**
     * Set the set of registers that we will read and read and read again on every hardware cycle 
     */
    public void setReadWindow(ReadWindow window)
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                if (this.readWindow == null || !this.readWindow.isOkToRead() || !this.readWindow.sameAs(window))
                    {
                    // Remember the new window, but get a fresh copy so we can implement the read mode policy
                    this.readWindow = window.freshCopy();
                    assertTrue(!BuildConfig.DEBUG || this.readWindow.isOkToRead());

                    // Let others know of the update
                    this.readWindowChanged = true;
                    }
                }
            }
        }

    /**
     * Return the current register window.
     */
    public ReadWindow getReadWindow()
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                return this.readWindow;
                }
            }
        }

    /**
     * Ensure that the current register window covers the indicated set of registers.
     */
    public void ensureReadWindow(ReadWindow windowNeeded, ReadWindow windowToSet)
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                if (this.readWindow == null || !this.readWindow.containsWithSameMode(windowNeeded))
                    {
                    setReadWindow(windowToSet);
                    }
                }
            }
        }

    /**
     * Read the byte at the indicated register.
     */
    public byte read8(int ireg)
        {
        return this.read(ireg, 1)[0];
        }

    /**
     * Read a contiguous set of registers
     */
    public byte[] read(int ireg, int creg)
        {
        return this.readTimeStamped(ireg, creg).data;
        }
    
    /**
     * Read a contiguous set of registers.
     */
    public TimestampedData readTimeStamped(int ireg, int creg)
        {
        try
            {
            synchronized (this.concurrentClientLock)
                {
                synchronized (this.callbackLock)
                    {
                    // Wait until the write cache isn't busy. This honors the visibility semantic
                    // we intend to portray, namely that issuing a read after a write has been
                    // issued will see the state AFTER the write has had a chance to take effect.
                    while (this.writeCacheStatus != WRITE_CACHE_STATUS.IDLE)
                        {
                        this.callbackLock.wait();
                        }

                    // If there's no read window given or what's there can't be read any more,
                    // make a new window automatically. Note that if you're doing repeat reads
                    // that we don't do that: in that case, you're responsible for calling
                    // ensureReadWindow() yourself.
                    if (this.readWindow == null || !this.readWindow.isOkToRead())
                        {
                        // If we can re-use the window that was there before that will help increase
                        // the chance that we don't need to take the time to switch the controller to
                        // read mode (with a different window) and thus can respond faster.
                        if (this.readWindow != null && this.readWindow.contains(ireg, creg))
                            {
                            assertTrue(!BuildConfig.DEBUG || this.readWindow.getReadMode()==READ_MODE.ONLY_ONCE);
                            setReadWindow(this.readWindow);
                            }
                        else
                            {
                            // Make a one-shot that just covers the data we need right now
                            setReadWindow(new ReadWindow(ireg, creg, READ_MODE.ONLY_ONCE));
                            }
                        }

                    // We can only fetch registers that lie within the current register window
                    if (!this.readWindow.contains(ireg, creg))
                        throw new IllegalArgumentException(String.format("read request (%d,%d) outside of read window (%d, %d)", ireg, creg, this.readWindow.getIregFirst(), this.readWindow.getCreg()));

                    // Wait until the read cache is valid
                    while (this.readWindowChanged || !this.readCacheStatus.isValid())
                        {
                        this.callbackLock.wait();
                        }

                    // Extract the data and return!
                    this.readCacheLock.lockInterruptibly();
                    try
                        {
                        assertTrue(!BuildConfig.DEBUG || this.readWindowActuallyRead.contains(this.readWindow));

                        // The data of interest is somewhere in the read window, but not necessarily at the start.
                        int ibFirst            = ireg - this.readWindowActuallyRead.getIregFirst() + dibCacheOverhead;
                        TimestampedData result = new TimestampedData();
                        result.data            = Arrays.copyOfRange(this.readCache, ibFirst, ibFirst + creg);
                        result.nanoTime        = this.nanoTimeReadCacheValid;
                        return result;
                        }
                    finally
                        {
                        this.readCacheLock.unlock();

                        // If that was a one-time read, invalidate the data so we won't read it again a second time.
                        // Note that this is the only place outside of the callback that we ever update
                        // readCacheStatus or writeCacheStatus
                        if (this.readCacheStatus==READ_CACHE_STATUS.VALID_ONLYONCE)
                            this.readCacheStatus=READ_CACHE_STATUS.IDLE;
                        }
                    }
                }
            }
        catch (InterruptedException e)
            {
            Util.handleCapturedInterrupt();

            // Can't return (no data to return!) so we must throw
            throw SwerveRuntimeException.wrap(e);
            }
        }

    /**
     * Write a byte to the indicated register
     */
    public void write8(int ireg, int data)
        {
        this.write(ireg, new byte[] {(byte) data});
        }
    public void write8(int ireg, int data, boolean waitforCompletion)
        {
        this.write(ireg, new byte[]{(byte) data}, waitforCompletion);
        }

    /**
     * Write data to a set of registers, beginning with the one indicated. The data will be
     * written to the I2C device as expeditiously as possible. This method will not return until
     * the data has been written to the device controller; however, that does not necessarily
     * indicate that the data has been issued in an I2C write transaction, though that ought
     * to happen a short deterministic time later.
     */
    public void write(int ireg, byte[] data)
        {
        write(ireg, data, true);
        }
    public void write(int ireg, byte[] data, boolean waitForCompletion)
        {
        try
            {
            synchronized (this.concurrentClientLock)
                {
                synchronized (this.callbackLock)
                    {
                    // Wait until we can write to the write cache
                    while (this.writeCacheStatus != WRITE_CACHE_STATUS.IDLE)
                        {
                        this.callbackLock.wait();
                        }

                    // Indicate where we want to write
                    this.iregWriteFirst = ireg;
                    this.cregWrite      = data.length;

                    // Indicate we are dirty so the callback will write us out
                    this.writeCacheStatus = WRITE_CACHE_STATUS.DIRTY;

                    // Provide the data we want to write
                    this.writeCacheLock.lockInterruptibly();
                    try
                        {
                        System.arraycopy(data, 0, this.writeCache, dibCacheOverhead, data.length);
                        }
                    finally
                        {
                        this.writeCacheLock.unlock();
                        }

                    // Let the callback know we've got new data for him
                    this.callback.onNewDataToWrite();

                    if (waitForCompletion)
                        {
                        // Wait until the write at least issues to the device controller. This will
                        // help make any delays/sleeps that follow a write() be more deterministically
                        // relative to the actual I2C device write.
                        while (writeCacheStatus != WRITE_CACHE_STATUS.IDLE)
                            {
                            this.callbackLock.wait();
                            }
                        }
                    }
                }
            }
        catch (InterruptedException e)
            {
            Util.handleCapturedInterrupt();
            }
        }
    
    public Thread getCallbackThread()
        {
        synchronized (this.callbackLock)
            {
            return this.callbackThread;
            }
        }
    
    public int getI2cCycleCount()
        {
        synchronized (this.callbackLock)
            {
            return this.hardwareCycleCount;
            }
        }
    
    public void setLogging(boolean enabled)
        {
        synchronized (this.callbackLock)
            {
            this.loggingEnabled = enabled;
            }
        }

    public void setLoggingTag(String loggingTag)
        {
        synchronized (this.callbackLock)
            {
            this.loggingTag = loggingTag;
            }
        }
    
    /* Disable, temporarily, to allow more thinking as to how best to model heartbeats

    public int getHeartbeatInterval()
        {
        synchronized (this.theLock)
            {
            return this.msHeartbeatInterval;
            }
        }

    public void setHeartbeatRead(int ms)
        {
        ms = Math.max(0, ms);
        synchronized (this.theLock)
            {
            this.msHeartbeatInterval = ms;
            this.heartBeatUsingRead  = true;
            }
        }
    
    public void setHeartbeatWrite(int ms)
        {
        ms = Math.max(0, ms);
        synchronized (this.theLock)
            {
            this.msHeartbeatInterval = ms;
            this.heartBeatUsingRead  = false;
            }
        }
    */

    private void log(int verbosity, String message)
        {
        switch (verbosity)
            {
        case Log.VERBOSE:   Log.v(loggingTag, message); break;
        case Log.DEBUG:     Log.d(loggingTag, message); break;
        case Log.INFO:      Log.i(loggingTag, message); break;
        case Log.WARN:      Log.w(loggingTag, message); break;
        case Log.ERROR:     Log.e(loggingTag, message); break;
        case Log.ASSERT:    Log.wtf(loggingTag, message); break;
            }
        }
    private void log(int verbosity, String format, Object... args)
        {
        log(verbosity, String.format(format, args));
        }
    
    /** Flag to distinguish state machine updates that are caused by the callback vs state
      * machine updates that are due to application-initiated writes */
    private enum UPDATE_STATE_MACHINE
        {
        FROM_CALLBACK,
        FROM_USER_WRITE
        }

    private class Callback implements I2cController.I2cPortReadyCallback
        {
        //------------------------------------------------------------------------------------------
        // State, kept in member variables so we can divy the updateStateMachines() logic
        // across multiple function
        //------------------------------------------------------------------------------------------

        boolean setActionFlag     = false;
        boolean queueFullWrite    = false;
        boolean queueRead         = false;
        boolean heartbeatRequired = false;
        boolean enabledReadMode   = false;
        boolean enabledWriteMode  = false;

        READ_CACHE_STATUS  prevReadCacheStatus  = READ_CACHE_STATUS.IDLE;
        WRITE_CACHE_STATUS prevWriteCacheStatus = WRITE_CACHE_STATUS.IDLE;
        MODE_CACHE_STATUS  prevModeCacheStatus  = MODE_CACHE_STATUS.IDLE;

        //------------------------------------------------------------------------------------------
        // Main entry points
        //------------------------------------------------------------------------------------------

        @Override public void portIsReady(int port)
        // This is the callback from the device module indicating completion of previously requested work.
        // At the moment we are called, we are assured that the read buffer / write buffer for our port in the
        // USB device is not currently busy.
            {
            updateStateMachines(UPDATE_STATE_MACHINE.FROM_CALLBACK);
            }

        // The user has new data for us to write. We could do nothing, in which case the data
        // will go out at the next callback cycle just fine, or we could try to push it out
        // more aggressively.
        void onNewDataToWrite()
            {
            updateStateMachines(UPDATE_STATE_MACHINE.FROM_USER_WRITE);
            }

        //------------------------------------------------------------------------------------------
        // Update logic
        //------------------------------------------------------------------------------------------

        void startSwitchingToReadMode()
            {
            readCacheStatus = READ_CACHE_STATUS.SWITCHINGTOREADMODE;
            i2cDevice.enableI2cReadMode(readWindow.getIregFirst(), readWindow.getCreg());
            enabledReadMode = true;

            // Remember what we actually told the controller
            readWindowSentToController = readWindow;

            setActionFlag   = true;     // causes an I2C read to happen
            queueFullWrite  = true;     // for just the mode bytes

            dirtyModeCacheStatus();
            }

        void issueWrite()
            {
            writeCacheStatus = WRITE_CACHE_STATUS.QUEUED;
            i2cDevice.enableI2cWriteMode(iregWriteFirst, cregWrite);
            enabledWriteMode = true;

            // This might be only paranoia, but we're not certain. In any case, it's safe.
            readWindowSentToController = null;

            setActionFlag  = true;      // causes the I2C write to happen
            queueFullWrite = true;      // for the mode bytes and the payload

            dirtyModeCacheStatus();
            }

        void dirtyModeCacheStatus()
            {
            assertTrue(!BuildConfig.DEBUG || modeCacheStatus == MODE_CACHE_STATUS.IDLE);
            modeCacheStatus = MODE_CACHE_STATUS.DIRTY;
            }

        private void clearActionFlag()
            {
            try {
                writeCacheLock.lock();
                writeCache[ibActionFlag] = 0;
                }
            finally
                {
                writeCacheLock.unlock();
                }
            }

        void dealWithHeartbeat()
        // This is not yet used; heartbeats are temporariliy disabled
        // heartbeat will reissue the last read or write, depending on what the MODULE
        // currently is doing.
            {
            if (setActionFlag)
                {
                // We're about to communicate right now, so reset the heart beat.
                // Note that we reset() *before* we talk to the device so as to do
                // conservative timing accounting
                timeSinceLastHeartbeat.reset();
                }

            if (!setActionFlag && heartbeatRequired && !heartBeatUsingRead)
                {
                // Rewrite what we previously wrote
                // TODO: is this really the best idea?
                setActionFlag  = true;
                queueFullWrite = true;
                }
            }

        void updateStateMachines(UPDATE_STATE_MACHINE caller)
        // We've got quite the little state machine here!
            {
            synchronized (callbackLock)
                {
                //----------------------------------------------------------------------------------
                // If we're calling from other than the callback (in which we *know* the port is
                // ready), we need to check whether things are currently busy. We defer until
                // later if they are.
                if (caller==UPDATE_STATE_MACHINE.FROM_USER_WRITE)
                    {
                    if (!i2cDevice.isI2cPortReady() || callbackThread==null)
                        return;

                    // Optimized calling from user mode is not yet implemented
                    return;
                    }

                //----------------------------------------------------------------------------------
                // Some ancillary bookkeeping

                if (caller == UPDATE_STATE_MACHINE.FROM_CALLBACK)
                    {
                    // Capture the current callback thread if we haven't already
                    if (callbackThread == null)
                        callbackThread = Thread.currentThread();
                    else
                        assertTrue(!BuildConfig.DEBUG || callbackThread.getId() == Thread.currentThread().getId());

                    // Set the thread name to make the system more debuggable
                    if (0 == hardwareCycleCount)
                        Thread.currentThread().setName(String.format("RWLoop(%s)", i2cDevice.getDeviceName()));

                    // Update cycle statistics
                    hardwareCycleCount++;
                    }

                //----------------------------------------------------------------------------------
                // Initialize state for managing state transition

                setActionFlag     = false;
                queueFullWrite    = false;
                queueRead         = false;
                heartbeatRequired = (msHeartbeatInterval > 0 && timeSinceLastHeartbeat.time()*1000 >= msHeartbeatInterval);
                enabledReadMode   = false;
                enabledWriteMode  = false;
                
                prevReadCacheStatus  = readCacheStatus;
                prevWriteCacheStatus = writeCacheStatus;
                prevModeCacheStatus  = modeCacheStatus;

                //----------------------------------------------------------------------------------
                // Handle the state machine

                if (caller==UPDATE_STATE_MACHINE.FROM_CALLBACK)
                    {
                    //--------------------------------------------------------------------------
                    // Deal with the fact that we've completed any previous queueing operation

                    if (modeCacheStatus == MODE_CACHE_STATUS.QUEUED)
                        modeCacheStatus = MODE_CACHE_STATUS.IDLE;

                    if (readCacheStatus == READ_CACHE_STATUS.QUEUED || readCacheStatus == READ_CACHE_STATUS.VALID_QUEUED)
                        {
                        readCacheStatus = READ_CACHE_STATUS.QUEUE_COMPLETED;
                        nanoTimeReadCacheValid = System.nanoTime();
                        }

                    if (writeCacheStatus == WRITE_CACHE_STATUS.QUEUED)
                        writeCacheStatus = WRITE_CACHE_STATUS.IDLE;

                    //--------------------------------------------------------------------------
                    // That limits the number of states the caches can now be in

                    assertTrue(!BuildConfig.DEBUG || (readCacheStatus==READ_CACHE_STATUS.IDLE
                                    ||readCacheStatus==READ_CACHE_STATUS.SWITCHINGTOREADMODE
                                    ||readCacheStatus==READ_CACHE_STATUS.VALID_ONLYONCE
                                    ||readCacheStatus==READ_CACHE_STATUS.QUEUE_COMPLETED));
                    assertTrue(!BuildConfig.DEBUG || (writeCacheStatus == WRITE_CACHE_STATUS.IDLE || writeCacheStatus == WRITE_CACHE_STATUS.DIRTY));

                    //--------------------------------------------------------------------------
                    // Complete any read mode switch if there is one

                    if (readCacheStatus == READ_CACHE_STATUS.SWITCHINGTOREADMODE)
                        {
                        // We're trying to switch into read mode. Are we there yet?
                        if (i2cDevice.isI2cPortInReadMode())
                            {
                            // See also below XYZZY
                            readCacheStatus = READ_CACHE_STATUS.QUEUED;
                            setActionFlag = true;       // actually do an I2C read
                            }
                        }

                    //--------------------------------------------------------------------------
                    // If there's a write request pending, and it's ok to issue the write, do so

                    else if (writeCacheStatus == WRITE_CACHE_STATUS.DIRTY)
                        {
                        issueWrite();

                        // Our ordering rules are that any reads after a write have to wait until
                        // the write is actually sent to the hardware, so anything we've read before is junk.
                        // Note that there's an analogous check in read().
                        readCacheStatus = READ_CACHE_STATUS.IDLE;
                        }

                    //--------------------------------------------------------------------------
                    // Initiate reading if we should. Be sure to honor the policy of the read mode

                    else if (readCacheStatus == READ_CACHE_STATUS.IDLE || readWindowChanged)
                        {
                        if (readWindow != null && readWindow.isOkToRead())
                            {
                            // We're going to read from this window. If it's an only-once, then
                            // ensure we don't come down this path again with the same ReadWindow instance.
                            readWindow.setReadIssued();

                            // You know...we might *already* have set up the controller to read what we want.
                            // Maybe the previous read was a one-shot, for example.
                            if (readWindowSentToController != null && readWindowSentToController.contains(readWindow) && i2cDevice.isI2cPortInReadMode())
                                {
                                // Lucky us! We can go ahead and queue the read right now!
                                // See also above XYZZY
                                readWindowActuallyRead = readWindowSentToController;
                                readCacheStatus = READ_CACHE_STATUS.QUEUED;
                                setActionFlag = true;       // actually do an I2C read
                                }
                            else
                                {
                                // We'll start switching now, and queue the read later
                                readWindowActuallyRead = readWindow;
                                startSwitchingToReadMode();
                                }
                            }
                        else
                            {
                            // There's nothing to read. Make *sure* we are idle.
                            readCacheStatus = READ_CACHE_STATUS.IDLE;
                            }

                        readWindowChanged = false;
                        }

                    //--------------------------------------------------------------------------
                    // Reissue any previous read if we should. The only way we are here and
                    // see READ_CACHE_STATUS.VALID_ONLYONCE is if we completed a queuing operation
                    // above.

                    else if (readCacheStatus == READ_CACHE_STATUS.QUEUE_COMPLETED)
                        {
                        if (readWindow != null && readWindow.isOkToRead())
                            {
                            readCacheStatus = READ_CACHE_STATUS.VALID_QUEUED;
                            setActionFlag = true;           // actually do an I2C read
                            }
                        else
                            {
                            readCacheStatus = READ_CACHE_STATUS.VALID_ONLYONCE;
                            }
                        }

                    //--------------------------------------------------------------------------
                    // Completing the possibilities:

                    else if (readCacheStatus == READ_CACHE_STATUS.VALID_ONLYONCE)
                        {
                        // Just leave it there until someone reads it
                        }

                    //--------------------------------------------------------------------------
                    // In all cases, we want to read the latest from the controller to get read
                    // vs write mode settings, if nothing else.

                    queueRead = true;
                    }

                else if (caller==UPDATE_STATE_MACHINE.FROM_USER_WRITE)
                    {
                    // This is not yet implemented
                    }

                //----------------------------------------------------------------------------------
                // Read, set action flag and / or queue to module as requested
                
                if (setActionFlag)
                    i2cDevice.setI2cPortActionFlag();
                else
                    clearActionFlag();

                if (setActionFlag && !queueFullWrite)
                    {
                    i2cDevice.writeI2cPortFlagOnlyToController();
                    }
                else if (queueFullWrite)
                    {
                    i2cDevice.writeI2cCacheToController();
                    //
                    if (modeCacheStatus == MODE_CACHE_STATUS.DIRTY)
                        modeCacheStatus =  MODE_CACHE_STATUS.QUEUED;
                    }

                // Queue a read after queuing any write for a bit of paranoia: if we're mode switching
                // to write, we want that write to go out first, THEN read the mode status. It probably
                // would anyway, but why not...
                if (queueRead)
                    {
                    i2cDevice.readI2cCacheFromController();
                    }

                //----------------------------------------------------------------------------------
                // Do logging

                if (loggingEnabled)
                    {
                    StringBuilder message = new StringBuilder();

                    switch (caller)
                        {
                        case FROM_CALLBACK:     message.append(String.format("cyc %d", hardwareCycleCount)); break;
                        case FROM_USER_WRITE:   message.append(String.format("usr write")); break;
                        }
                    if (setActionFlag)                            message.append("|flag");
                    if (setActionFlag && !queueFullWrite)         message.append("|f");
                    else if (queueFullWrite)                      message.append("|w");
                    else                                          message.append("|.");
                    if (queueRead)                                message.append("|r");
                    if (readCacheStatus != prevReadCacheStatus)   message.append("| R." + prevReadCacheStatus.toString() + "->" + readCacheStatus.toString());
                    if (writeCacheStatus != prevWriteCacheStatus) message.append("| W." + prevWriteCacheStatus.toString() + "->" + writeCacheStatus.toString());
                 // if (modeCacheStatus != prevModeCacheStatus)   message.append("| M." + prevModeCacheStatus.toString() + "->" + modeCacheStatus.toString());
                    if (enabledWriteMode)                         message.append(String.format("| setWrite(0x%02x,%d)", iregWriteFirst, cregWrite));
                    if (enabledReadMode)                          message.append(String.format("| setRead(0x%02x,%d)", readWindow.getIregFirst(), readWindow.getCreg()));

                    log(Log.DEBUG, message.toString());
                    }

                //----------------------------------------------------------------------------------
                // Notify anyone blocked in read() or write()
                callbackLock.notifyAll();
                }
            }
        }
    }
