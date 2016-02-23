/*
 * Copyright 2016 The OpenDCT Authors. All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opendct.video.ffmpeg;

import opendct.consumer.buffers.FFmpegCircularBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.avformat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avutil.*;

public class FFmpegContext {
    private final Logger logger = LogManager.getLogger(FFmpegContext.class);

    private final static ReentrantReadWriteLock contextLock = new ReentrantReadWriteLock();
    private final static HashMap<Pointer, FFmpegContext> contextMap = new HashMap<>();
    private final static HashMap<Pointer, FFmpegWriter> writerMap = new HashMap<>();
    private final static AtomicLong callbackAddress = new AtomicLong();
    public final Pointer OPAQUE;
    private Pointer writerOpaque;

    public final FFmpegWriter FFMPEG_WRITER;
    public final StringBuilder DUMP_BUFFER;
    public final FFmpegCircularBuffer SEEK_BUFFER;
    public final FFmpegStreamProcessor STREAM_PROCESSOR;
    public final int RW_BUFFER_SIZE;

    private final AVIOInterruptCB interruptCB;
    private boolean interrupted;
    private boolean disposed;

    // These are left protected because a getter and setter will just add latency.
    protected AVFormatContext avfCtxInput;
    protected AVFormatContext avfCtxOutput;
    protected AVIOContext avioCtxInput;
    protected AVIOContext avioCtxOutput;

    protected int preferredVideo;
    protected int preferredAudio;

    // These are freed on av_close_input_file.
    protected AVStream videoStream;
    protected AVStream audioStream;
    protected AVCodecContext videoCodecCtx;
    protected AVCodecContext audioCodecCtx;

    // Used for returning increasing probe sizes so we don't probe more than we need to probe.
    private final Object nativeSync = new Object();
    private int nativeFileProbeLimit;
    private int nativeLastProbeSize;
    private int nativeIncrement;

    public static final int FILE_MODE_MPEGTS = 0;
    public static final int FILE_MODE_NATIVE = 1;
    protected int inputFileMode;
    protected String inputFilename;
    protected String outputFilename;
    protected int desiredProgram;
    protected int streamMap[];
    protected AVCodec encoderCodecs[];
    protected AVDictionary encoderDicts[];
    protected boolean encodeMap[];

    static {
        FFmpegUtil.initAll();
    }

    /**
     * Create a new FFmpeg context.
     *
     * @param bufferSize The buffer size to used. This value will be overridden to 2246948 if it is
     *                   less than 2246948.
     * @param rwBufferSize The native buffer size to be used for reading and writing. This value
     *                     will be overridden to 10340 if it is less than 10340.
     */
    public FFmpegContext(int bufferSize, int rwBufferSize, FFmpegStreamProcessor streamProcessor, FFmpegWriter writer) {
       this(
               new FFmpegCircularBuffer(bufferSize < 2246948 ? 2246948 : bufferSize),
               rwBufferSize < 10340 ? 10340 : rwBufferSize,
               streamProcessor,
               writer
       );
    }

    /**
     * Create a new FFmpeg context.
     *
     * @param seekBuffer An already initialized buffer or <i>null</i> if the native reading method
     *                   will be used.
     * @param rwBufferSize The native buffer size to be used for reading and writing. This value
     *                     will be overridden to 10340 if it is less than 10340.
     */
    public FFmpegContext(FFmpegCircularBuffer seekBuffer, int rwBufferSize, FFmpegStreamProcessor streamProcessor, FFmpegWriter writer) {
        disposed = false;

        FFMPEG_WRITER = writer;
        SEEK_BUFFER = seekBuffer;
        STREAM_PROCESSOR = streamProcessor;
        OPAQUE = setContext();
        writerOpaque = null;

        DUMP_BUFFER = new StringBuilder(2000);

        interrupted = false;
        interruptCB = new avformat.AVIOInterruptCB();
        interruptCB.callback(interruptCallback);
        interruptCB.opaque(OPAQUE);

        RW_BUFFER_SIZE = rwBufferSize;

        nativeFileProbeLimit = 0;
        nativeLastProbeSize = 0;
        nativeIncrement = 1123474;

        inputFileMode = SEEK_BUFFER != null ? FILE_MODE_MPEGTS : FILE_MODE_NATIVE;
        inputFilename = null;
        outputFilename = null;
        desiredProgram = 0;

        preferredVideo = -1;
        preferredAudio = -1;
        videoStream = null;
        audioStream = null;
        videoCodecCtx = null;
        audioCodecCtx = null;

        streamMap = new int[0];
        encodeMap = new boolean[0];
        encoderCodecs = new AVCodec[0];
        encoderDicts = new AVDictionary[0];
    }

    public static FFmpegContext getContext(Pointer opaque) {
        FFmpegContext returnValue;

        contextLock.readLock().lock();

        try {
            returnValue = contextMap.get(opaque);
        } finally {
            contextLock.readLock().unlock();
        }

        return returnValue;
    }

    private Pointer setContext() {
        Pointer opaque;

        contextLock.writeLock().lock();

        try {
            opaque = new Pointer(new TmpPointer());
            contextMap.put(opaque, this);
        } finally {
            contextLock.writeLock().unlock();
        }

        return opaque;
    }

    public static FFmpegWriter getWriterContext(Pointer opaque) {
        FFmpegWriter returnValue;

        contextLock.readLock().lock();

        try {
            returnValue = writerMap.get(opaque);
        } finally {
            contextLock.readLock().unlock();
        }

        return returnValue;
    }

    private Pointer setWriterContext(FFmpegWriter writer) {
        Pointer opaque;

        contextLock.writeLock().lock();

        try {
            opaque = new Pointer(new TmpPointer());
            writerMap.put(opaque, writer);
        } finally {
            contextLock.writeLock().unlock();
        }

        return opaque;
    }

    public void removeWriterContext(Pointer opaque) {
        contextLock.writeLock().lock();

        try {
            writerMap.remove(opaque);
        } finally {
            contextLock.writeLock().unlock();
        }
    }

    private class TmpPointer extends Pointer {
        TmpPointer() {
            address = callbackAddress.incrementAndGet();
        }
    }

    /**
     * Is this thread currently interrupted?
     *
     * @return <i>true</i> if the thread is interrupted or has been interrupted.
     */
    public boolean isInterrupted() {
        boolean isInterrupted = Thread.currentThread().isInterrupted() || SEEK_BUFFER.isClosed();
        if (isInterrupted) {
            interrupted = true;
        }
        return isInterrupted;
    }

    protected static final avformat.AVIOInterruptCB.Callback_Pointer interruptCallback = new InterruptCallable();

    protected static class InterruptCallable extends avformat.AVIOInterruptCB.Callback_Pointer {
        @Override
        public int call(Pointer opaque) {
            FFmpegContext context = getContext(opaque);

            if (context.interrupted) {
                context.logger.info("Interrupt callback is returning 1");
                return 1;
            }

            return 0;
        }
    }

    protected static Seek_Pointer_long_int seekCallback = new SeekCallback();

    protected static class SeekCallback extends Seek_Pointer_long_int {
        @Override
        public long call(Pointer opaque, long offset, int whence) {
            FFmpegContext context = getContext(opaque);

            long returnValue = -1;

            try {
                returnValue = context.SEEK_BUFFER.seek(whence, offset);
            } catch (Exception e) {
                context.logger.error("There was an exception while seeking => ", e);
            }

            return returnValue;
        }
    }

    protected static Read_packet_Pointer_BytePointer_int readCallback = new ReadCallback();

    protected static class ReadCallback extends Read_packet_Pointer_BytePointer_int {
        @Override
        public int call(Pointer opaque, BytePointer buf, int bufSize) {
            FFmpegContext context = getContext(opaque);

            int nBytes = -1;

            try {
                ByteBuffer readBuffer = buf.position(0).limit(bufSize).asBuffer();

                nBytes = context.SEEK_BUFFER.read(readBuffer);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    context.interrupted = true;
                    context.SEEK_BUFFER.close();
                    context.logger.debug("FFmpeg consumer was interrupted while reading.");
                } else {
                    context.logger.error("FFmpeg consumer was closed while reading by an exception => ", e);
                }
            }

            if (nBytes == -1) {
                context.logger.info("Returning AVERROR_EOF in readCallback.call()");
                return AVERROR_EOF;
            }

            return nBytes;
        }
    }

    protected static Write_packet_Pointer_BytePointer_int writeCallback = new WriteCallback();

    protected static class WriteCallback extends Write_packet_Pointer_BytePointer_int {

        @Override
        public int call(Pointer opaque, BytePointer buf, int bufSize) {
            FFmpegWriter context = getWriterContext(opaque);
            Logger logger = context.getLogger();

            int numBytesWritten = 0;

            try {
                ByteBuffer writeBuffer = buf.position(0).limit(bufSize).asByteBuffer();

                //TODO: Add file switching mechanism.
                numBytesWritten = context.write(writeBuffer);
            } catch (IOException e) {
                if (logger != null) {
                    logger.error("'{}' experienced an IOException => ", context.getClass(), e);
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.error("'{}' experienced an unhandled exception => ", context.getClass(), e);
                }
            }

            if (logger != null && logger.isTraceEnabled()) {
                logger.trace("writeCallback called to write {} bytes. Wrote {} bytes.", bufSize, numBytesWritten);
            }

            if (numBytesWritten < 0) {
                if (logger != null) {
                    logger.info("Returning AVERROR_EOF in writeCallback.call()");
                }
                return AVERROR_EOF;
            }

            return numBytesWritten;
        }
    }


    public void setProbeData(String filename) {
        if (filename != null && (inputFilename == null || !inputFilename.equals(filename))) {
            long fileLength = new File(filename).length();
            nativeFileProbeLimit = fileLength > 20971520 ? 20971520 : (int) fileLength;
            nativeIncrement = 1123474;

            inputFilename = filename;
        }
    }

    public int getProbeAvailable() {
        switch (inputFileMode) {
            case FILE_MODE_MPEGTS:
                return SEEK_BUFFER.readAvailable();
            case FILE_MODE_NATIVE:
                synchronized (nativeSync) {
                    if (inputFilename != null) {
                        if (nativeLastProbeSize == nativeFileProbeLimit) {
                            return nativeLastProbeSize;
                        }

                        nativeLastProbeSize += nativeIncrement;

                        // The data available increment is increased with each call to this method
                        // so we will only read the same data up to 6 times instead of 20.
                        nativeIncrement *= 2;

                        // This makes sure we never return a number greater than the probe limit or
                        // a negative number.
                        nativeLastProbeSize = Math.max(
                                1123474,
                                Math.min(
                                        nativeLastProbeSize,
                                        nativeFileProbeLimit)
                        );

                        return nativeLastProbeSize;
                    }
                }

                return 0;
            default:
                return 0;
        }
    }

    public int getProbeMaxSize() {
        switch (inputFileMode) {
            case FILE_MODE_MPEGTS:
                return SEEK_BUFFER.getBufferMaxSize() - 1123474;
            case FILE_MODE_NATIVE:
                synchronized (nativeSync) {
                    if (inputFilename != null) {
                        return nativeFileProbeLimit;
                    }
                }

                return 0;
            default:
                return 0;
        }
    }

    /**
     * Detect if we have enough stream data to start transcoding/remuxing.
     * <p/>
     * If needed, ensure that the desired program is set before running this method.
     *
     * @return <i>true</i> if all required streams are available. If <i>false</i> is returned, all
     *         allocated resources will already be de-allocated.
     */
    public boolean initTsStream() {
        if (SEEK_BUFFER == null) return false;
        inputFileMode = FILE_MODE_MPEGTS;

        String error[] = new String[1];

        while (!isInterrupted()) {
            try {
                if (!FFmpegStreamDetection.detectStreams(this, null, error)) {
                    if (!isInterrupted()) {
                        logger.error("initTsStream: {}", error[0]);

                        // Clear the buffer in hopes the new data will work out better.
                        SEEK_BUFFER.clear();

                        continue;
                    } else {
                        logger.debug("initTsStream: {}", error[0]);
                        return false;
                    }
                }

                //allocAvfContainerOutputContext(outputFilename, desiredProgram);
            } catch (IllegalStateException e) {
                logger.error("initTsStream: {} => ", error[0], e);
                return false;
            }

            break;
        }

        return true;
    }

    /**
     * Detect if we have enough stream data to start transcoding/remuxing.
     * <p/>
     * If needed, ensure that the desired program is set before running this method.
     *
     * @param nativeFile The full path to the file to be opened.
     * @return <i>true</i> if all required streams are available. If <i>false</i> is returned, all
     *         allocated resources will already be de-allocated.
     */
    public boolean initNativeFile(String nativeFile) {
        String error[] = new String[1];
        inputFileMode = FILE_MODE_NATIVE;

        try {
            if (!FFmpegStreamDetection.detectStreams(this, nativeFile, error)) {
                if (!isInterrupted()) {
                    logger.error("initNativeFile: {}", error[0]);
                } else {
                    logger.debug("initNativeFile: {}", error[0]);
                }

                // This is a static file and nothing will change between passes, so stop here.
                return false;
            }
        } catch (IllegalStateException e) {
            logger.error("initNativeFile: {} => ", error[0], e);
            return false;
        }

        return true;
    }

    /**
     * Create a new input AVIOContext assign it and the interrupt callback to a new AVFormatContext.
     * <p/>
     * If AVIOContext or AVFormatContext are already allocated, they will be de-allocated. This is
     * built in because these Contexts are constantly being allocated and de-allocated during stream
     * detection.
     * <p/>
     * The input filename is implicitly set to input-mpeg-ts.ts since this method is only intended
     * for the use of bringing data in from MPEG-TS streams.
     * <p/>
     * Calling this method will change the file input mode to MPEG-TS.
     *
     * @throws IllegalStateException Thrown if any of the contexts cannot be allocated. AVIOContext
     *                               and AVIOContext contexts are de-allocated on exception.
     */
    protected void allocInputIoTsFormatContext() throws IllegalStateException {
        deallocInputContext();

        avioCtxInput = allocIoContext(OPAQUE, readCallback, null, seekCallback);

        if (avfCtxInput == null) {
            deallocInputContext();
            throw new IllegalStateException("FFmpeg input AVIOContext could not be allocated.");
        }

        avfCtxInput = avformat_alloc_context();

        if (avfCtxInput == null) {
            deallocInputContext();
            throw new IllegalStateException("FFmpeg input AVFormatContext could not be allocated.");
        }

        avfCtxInput.pb(avioCtxInput);
        avfCtxInput.interrupt_callback(interruptCB);

        inputFileMode = FILE_MODE_MPEGTS;
        inputFilename = "input-mpeg-ts.ts";
    }

    /**
     * Create a new input AVFormatContext using native file input and assigns the interrupt
     * callback to it.
     * <p/>
     * If AVIOContext or AVFormatContext are already allocated, they will be de-allocated. This is
     * built in because these contexts are constantly being allocated and de-allocated during stream
     * detection.
     * <p/>
     * Successfully calling this method will change the file input mode to native and the input
     * filename will be change to the provided filename.
     *
     * @throws IllegalStateException Thrown if any of the contexts cannot be allocated. AVIOContext
     *                               and AVIOContext contexts are de-allocated on exception.
     */
    protected void allocInputFormatNativeContext(String filename, AVDictionary dict) throws IllegalStateException {
        deallocInputContext();

        int ret;

        //avfCtxInput = avformat_alloc_context();
        avfCtxInput = new AVFormatContext(null);

        ret = avformat_open_input(avfCtxInput, filename, null, dict);
        if (ret < 0) {
            deallocInputContext();
            throw new IllegalStateException("Cannot open input file. Error " + ret);
        }

        /*ret = avformat_find_stream_info(avfCtxInput, (PointerPointer<AVDictionary>) null);
        if (ret < 0) {
            deallocInputContext();
            throw new IllegalStateException("Cannot find stream information. Error " + ret);
        }*/

        avfCtxInput.interrupt_callback(interruptCB);
        avioCtxInput = avfCtxInput.pb();

        setProbeData(filename);
        inputFileMode = FILE_MODE_NATIVE;
        inputFilename = filename;
    }

    /**
     * Dump the input format information for this context into the log.
     */
    public void dumpInputFormat() {
        DUMP_BUFFER.setLength(0);
        FFmpegUtil.dumpFormat(DUMP_BUFFER, avfCtxInput, 0, inputFilename, false, desiredProgram);
        logger.info(DUMP_BUFFER.toString());
    }

    /**
     * Dump the output format information for this context into the log.
     */
    public void dumpOutputFormat() {
        DUMP_BUFFER.setLength(0);
        FFmpegUtil.dumpFormat(DUMP_BUFFER, avfCtxOutput, 0, outputFilename, true, desiredProgram);
        logger.info(DUMP_BUFFER.toString());
    }

    /**
     * Create a new output AVFormatContext and initialize it for a specific container based on the
     * provided filename. This method only allows for MPEG-TS (default) and MPEG-PS (*.mpg).
     * <p/>
     * If AVFormatContext is already allocated, it must be de-allocated before using this method.
     *
     * @param filename The filename to use to determine the output format. This value can be
     *                 <i>null</i> if the default of MPEG-TS is desired.
     * @throws IllegalStateException Thrown if any of the contexts cannot be allocated. AVIOContext
     *                               context is de-allocated on exception.
     */
    public void allocAvfContainerOutputContext(String filename) throws IllegalStateException {
        avfCtxOutput = new AVFormatContext(null);

        outputFilename = filename != null ? filename : "output.ts";

        logger.debug("Calling avformat_alloc_output_context2");

        int ret;
        if (outputFilename.endsWith(".mpg")) {
            ret = avformat_alloc_output_context2(avfCtxOutput, null, "vob", null);
        } else {
            ret = avformat_alloc_output_context2(avfCtxOutput, null, null, "output.ts");
        }

        if (ret < 0) {
            deallocOutputContext();
            outputFilename = null;
            throw new IllegalStateException("avformat_alloc_output_context2 returned error code " + ret);
        }
    }

    /**
     * Allocates output AVIOContext and assigns it a write callback.
     * <p/>
     * The output AVFormatContext context must already be allocated before calling the method. If
     * the output AVIOContext is already allocated, it must be de-allocated before calling this method.
     *
     * @param writer The writer implementation to be used.
     * @throws IllegalStateException Thrown if any of the contexts cannot be allocated or output
     *                               AVFContext context is not already allocated. AVIOContext
     *                               context is de-allocated on exception.
     */
    public void allocIoOutputContext(FFmpegWriter writer) throws FFmpegException {

        if (writerOpaque != null) {
            removeWriterContext(writerOpaque);
        }

        if (avfCtxOutput == null) {
            throw new FFmpegException("FFmpeg output AVFContext must already be allocated.", -1);
        }

        Pointer opaque = setWriterContext(writer);
        avioCtxOutput = allocIoContext(opaque, null, writeCallback, null);

        if (avioCtxOutput == null) {
            removeWriterContext(opaque);
            throw new FFmpegException("FFmpeg output AVIOContext could not be allocated.", -1);
        }

        avfCtxOutput.pb(avioCtxOutput);
        avfCtxOutput.interrupt_callback(interruptCB);
    }

    /**
     * Allocates a custom writer that will not be de-allocated by this context.
     * <p/>
     * When this AVFormatContext is de-allocated, don't forget to remove it from the writer hashmap
     * or it may never be garbage collected.
     *
     * @param writer The writer implementation.
     * @param avfOutput The pre-allocated output AVFormatContext to assign the callback.
     * @return The pointer to be used for the writer callback. Retain this pointer to remove it
     *         from the writer hashmap once it is no longer needed.
     * @throws FFmpegException Thrown if the context cannot be allocated. AVFormatContext will not
     *                         be changed.
     */
    public Pointer allocCustomIoOutputContext(FFmpegWriter writer, AVFormatContext avfOutput) throws FFmpegException {
        if (avfOutput == null) {
            throw new FFmpegException("av_mallocz: FFmpeg output Pointer could not be allocated.", -1);
        }

        Pointer opaque = setWriterContext(writer);

        Pointer ptr = av_mallocz(RW_BUFFER_SIZE + AV_INPUT_BUFFER_PADDING_SIZE);

        if (ptr.isNull()) {
            removeWriterContext(opaque);
            throw new FFmpegException("av_mallocz: FFmpeg output Pointer could not be allocated.", -1);
        }

        AVIOContext avioCtx = avio_alloc_context(new BytePointer(ptr), RW_BUFFER_SIZE, AVIO_FLAG_WRITE, opaque, null, writeCallback, null);

        if (avioCtx.isNull()) {
            removeWriterContext(opaque);
            av_free(ptr);
            throw new FFmpegException("avio_alloc_context: FFmpeg output AVIOContext could not be allocated.", -1);
        }

        avfOutput.pb(avioCtx);
        avfOutput.interrupt_callback(interruptCB);

        return opaque;
    }

    private AVIOContext allocIoContext(Pointer opaque, avformat.Read_packet_Pointer_BytePointer_int readCallback, avformat.Write_packet_Pointer_BytePointer_int writeCallback, avformat.Seek_Pointer_long_int seekCallback) {
        Pointer ptr = av_mallocz(RW_BUFFER_SIZE + AV_INPUT_BUFFER_PADDING_SIZE);

        if (ptr.isNull()) {
            return null;
        }

        AVIOContext avioCtx = avio_alloc_context(new BytePointer(ptr), RW_BUFFER_SIZE, AVIO_FLAG_WRITE, opaque, readCallback, writeCallback, seekCallback);

        if (avioCtx.isNull()) {
            av_free(ptr);

            if (writeCallback == null) {
                deallocInputContext();
            } else {
                deallocOutputContext();
            }

            return null;
        }

        return avioCtx;
    }

    public void deallocInputContext() {
        if (avfCtxInput != null && !avfCtxInput.isNull()) {
            avformat_close_input(avfCtxInput);
            avfCtxInput = null;
        }
    }

    public void deallocOutputContext() {
        if (avfCtxOutput != null && !avfCtxOutput.isNull()) {

            if ((avfCtxOutput.oformat().flags() & AVFMT_NOFILE) != 0) {
                avio_closep(avfCtxOutput.pb());
                avioCtxOutput = null;
            }

            avformat_free_context(avfCtxOutput);
            avfCtxOutput = null;
        }

        for (AVCodec encoderCodec : encoderCodecs) {
            encoderCodec = null;
        }

        for (AVDictionary encoderDict : encoderDicts) {
            if (encoderDict != null && !encoderDict.isNull()) {
                logger.debug("Calling av_dict_free");
                av_dict_free(encoderDict);
            }
            encoderDict = null;
        }
    }

    /**
     * De-allocates any objects that may be allocated.
     */
    public synchronized void deallocAll() {
        deallocInputContext();
        deallocOutputContext();
    }

    /**
     * This releases anything that could lead to a memory leak from this context.
     * <p/>
     * After calling this method you can no longer use this context. Attempts to use this context
     * after calling this method will be unpredictable.
     */
    public synchronized void dispose() {
        if (!disposed) {
            contextLock.writeLock().lock();

            try {
                contextMap.remove(OPAQUE);
            } finally {
                contextLock.writeLock().unlock();
            }
        }

        if (writerOpaque != null) {
            removeWriterContext(writerOpaque);
        }

        deallocAll();
    }

    @Override
    protected void finalize() throws Throwable {
        // Not sure this is completely necessary.
        //dispose();

        super.finalize();
    }
}
