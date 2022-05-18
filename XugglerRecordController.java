package com.pedro.rtpstreamer;


import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pedro.rtplibrary.base.recording.BaseRecordController;
import com.xuggle.ferry.IBuffer;
import com.xuggle.xuggler.IAudioSamples;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IContainer;
import com.xuggle.xuggler.IContainerFormat;
import com.xuggle.xuggler.IPacket;
import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IRational;
import com.xuggle.xuggler.IStreamCoder;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public class XugglerRecordController extends BaseRecordController {

    private IContainer container;
    private IStreamCoder videoEncoder;
    private IStreamCoder audioEncoder;
    private IRational timebase = IRational.make(1, 1000 * 1000);
    private IContainerFormat format = IContainerFormat.make();
    private ConcurrentLinkedQueue queue = new ConcurrentLinkedQueue<IPacket>();

    private String outputUrl;
    private CountDownLatch semaphore;
    private boolean isOnlyVideo;
    private boolean isOnlyAudio;

    public XugglerRecordController() {
        format.setOutputFormat("mp4", null, null);
    }

    @Override
    public void startRecord(@NonNull String path, @Nullable Listener listener) throws IOException {
        outputUrl = path;

        semaphore = new CountDownLatch(1);
        container = IContainer.make();
        container.open(outputUrl, IContainer.Type.WRITE, format);
        container.setStandardsCompliance(IStreamCoder.CodecStandardsCompliance.COMPLIANCE_STRICT);
        container.setProperty("movflags", "frag_keyframe+empty_moov+faststart");

        status = Status.STARTED;
        if (listener != null)
            listener.onStatusChange(status);
    }

    @Override
    public void startRecord(@NonNull FileDescriptor fd, @Nullable Listener listener) throws IOException {
        throw new IOException("NON IMPLEMENTED");
    }


    private void initRecording() {

        status = Status.RECORDING;
        if (listener != null)
            listener.onStatusChange(status);

        Executors.newSingleThreadExecutor().execute(() -> {
            while (status == Status.RECORDING) {
                IPacket packet = (IPacket) queue.poll();
                if (packet != null)
                    container.writePacket(packet);
            }

            try {
                container.writeTrailer();
                audioEncoder.close();
                videoEncoder.close();
                container.close();
            } catch (Exception t) {
                t.printStackTrace();
            }
        });
    }


    @Override
    public void recordVideo(ByteBuffer videoBuffer, MediaCodec.BufferInfo videoInfo) {

        try {
            if (!isRecorderConfigured())
                semaphore.await();

            if (!container.isHeaderWritten()) {
                container.writeHeader();
                initRecording();
            }


            if (status == Status.RECORDING) {
                updateFormat(this.videoInfo, videoInfo);
                write(videoTrack, videoBuffer, this.videoInfo);
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }

    }

    @Override
    public void recordAudio(ByteBuffer audioBuffer, MediaCodec.BufferInfo audioInfo) {
        if (isOnlyAudio && !container.isHeaderWritten()) {
            container.writeHeader();
            initRecording();
        }


        if (status == Status.RECORDING) {
            updateFormat(this.audioInfo, audioInfo);
            write(audioTrack, audioBuffer, this.audioInfo);
        }
    }

    @Override
    public void setVideoFormat(MediaFormat videoFormat, boolean isOnlyVideo) {
        ICodec videoCodec = ICodec.findEncodingCodec(ICodec.ID.AV_CODEC_ID_H264);
        videoEncoder = IStreamCoder.make(IStreamCoder.Direction.ENCODING, videoCodec);
        container.addNewStream(videoEncoder);
        videoEncoder.setBitRate(videoFormat.getInteger(MediaFormat.KEY_BIT_RATE));
        videoEncoder.setPixelType(IPixelFormat.Type.YUV420P);
        videoEncoder.setHeight(videoFormat.getInteger(MediaFormat.KEY_WIDTH));
        videoEncoder.setWidth(videoFormat.getInteger(MediaFormat.KEY_HEIGHT));
        videoEncoder.setNumPicturesInGroupOfPictures(videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
        IRational frameRate = IRational.make(1, videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
        videoEncoder.setFrameRate(frameRate);
        videoEncoder.setTimeBase(timebase);
        videoTrack = 0;
        videoEncoder.open();
        this.isOnlyVideo = isOnlyVideo;
        checkTracksConfiguration();
    }

    @Override
    public void setAudioFormat(MediaFormat audioFormat, boolean isOnlyAudio) {
        ICodec audioCodec = ICodec.findEncodingCodec(ICodec.ID.AV_CODEC_ID_AAC);
        audioEncoder = IStreamCoder.make(IStreamCoder.Direction.ENCODING, audioCodec);
        container.addNewStream(audioEncoder);
        audioEncoder.setSampleRate(audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
        audioEncoder.setSampleFormat(IAudioSamples.Format.FMT_FLTP);
        audioEncoder.setTimeBase(timebase);
        audioEncoder.setBitRate(audioFormat.getInteger(MediaFormat.KEY_BIT_RATE));
        audioEncoder.setChannels(audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
        audioEncoder.setProperty("ch_mode", "indep");
        if (isOnlyAudio)
            audioTrack = 0;
        else
            audioTrack = 1;
        audioEncoder.open();
        this.isOnlyAudio = isOnlyAudio;
        checkTracksConfiguration();
    }

    private boolean isRecorderConfigured() {
        return isOnlyAudio && audioTrack != -1 || isOnlyVideo && videoTrack != -1 ||
                audioTrack != -1 && videoTrack != -1;
    }


    private void checkTracksConfiguration() {
        if (isRecorderConfigured())
            semaphore.countDown();
    }

    @Override
    public void resetFormats() {

    }


    public synchronized void write(int track, ByteBuffer byteBuffer, MediaCodec.BufferInfo info) {
        if (!container.isHeaderWritten())
            container.writeHeader();

        IPacket packet = IPacket.make();

        byte[] bytesArray = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytesArray, 0, bytesArray.length);

        packet.setData(IBuffer.make(null, bytesArray, 0, info.size));
        packet.setTimeStamp(info.presentationTimeUs);
        packet.setTimeBase(timebase);
        packet.setStreamIndex(track);

        if (packet.isComplete())
            queue.offer(packet);

    }


    @Override
    public void stopRecord() {
        status = Status.STOPPED;
        pauseMoment = 0;
        isOnlyAudio = false;
        isOnlyVideo = false;
        audioTrack = -1;
        videoTrack = -1;
        pauseTime = 0;
        if (listener != null)
            listener.onStatusChange(status);
    }


}