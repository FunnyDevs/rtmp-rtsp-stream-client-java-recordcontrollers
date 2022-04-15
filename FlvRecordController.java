package com.pedro.rtpstreamer;

import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPacker.INTER_FRAME;
import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPacker.KEY_FRAME;
import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPackerHelper.AUDIO_HEADER_SIZE;
import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPackerHelper.AUDIO_SPECIFIC_CONFIG_SIZE;
import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPackerHelper.FLV_HEAD_SIZE;
import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPackerHelper.FLV_TAG_HEADER_SIZE;
import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPackerHelper.PRE_SIZE;
import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPackerHelper.VIDEO_HEADER_SIZE;
import static com.laifeng.sopcastsdk.stream.packer.flv.FlvPackerHelper.VIDEO_SPECIFIC_CONFIG_EXTEND_SIZE;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.laifeng.sopcastsdk.stream.packer.AnnexbHelper;
import com.laifeng.sopcastsdk.stream.packer.flv.FlvPackerHelper;
import com.pedro.rtplibrary.base.recording.BaseRecordController;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;


public class FlvRecordController extends BaseRecordController implements AnnexbHelper.AnnexbNaluListener {

    private boolean isHeaderWrite;
    private boolean isKeyFrameWrite;
    private long startTime;
    private int videoWidth, videoHeight, videoFps;
    private int audioSampleRate, audioSampleSize;
    private boolean isStereo;

    private AnnexbHelper mAnnexbHelper;
    private boolean isPrerecording;
    private FileOutputStream outputStream;

    private int videoTrack = 0;
    private int audioTrack = 1;



    public FlvRecordController(int videoWidth, int videoHeight, int videoFps,
                               int audioSampleRate, int audioSampleSize, boolean isStereo){
        this.videoFps = videoFps;
        this.videoHeight = videoHeight;
        this.videoWidth = videoWidth;
        this.audioSampleRate = audioSampleRate;
        this.audioSampleSize = audioSampleSize;
        this.isStereo = isStereo;
    }


    public void init() {
        status = Status.RECORDING;
    }

    @Override
    public void startRecord(@NonNull String path, @Nullable Listener listener) throws IOException {
        mAnnexbHelper = new AnnexbHelper();
        mAnnexbHelper.setAnnexbNaluListener(this);
        outputStream = new FileOutputStream(path);
        init();
    }

    @Override
    public void startRecord(@NonNull FileDescriptor fd, @Nullable Listener listener) throws IOException {

    }

    @Override
    public void recordVideo(ByteBuffer videoBuffer, MediaCodec.BufferInfo videoInfo) {
        mAnnexbHelper.analyseVideoData(videoBuffer,videoInfo);
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onVideo(byte[] video, boolean isKeyFrame) {
        if(!isHeaderWrite) {
            return;
        }


        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        bufferInfo.size = video.length;
        if (isKeyFrame)
            bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;

        write(videoTrack,ByteBuffer.wrap(video),bufferInfo);

    }

    @Override
    public void recordAudio(ByteBuffer bb, MediaCodec.BufferInfo bi) {
        if(!isHeaderWrite || !isKeyFrameWrite) {
            return;
        }

        write(audioTrack,bb,bi);

    }

    @Override
    public void setVideoFormat(MediaFormat videoFormat, boolean isOnlyVideo) {

    }

    @Override
    public void setAudioFormat(MediaFormat audioFormat, boolean isOnlyVideo) {

    }

    @Override
    public void resetFormats() {

    }


    public synchronized void write(int track, ByteBuffer byteBuffer, MediaCodec.BufferInfo info) {
        try {

            long timestamp = System.currentTimeMillis() - startTime;
            info.presentationTimeUs = timestamp;
            if (track == audioTrack)
                outputStream.write(prepareAudioData(byteBuffer,info).array());
            else{
                ByteBuffer data = prepareVideoData(byteBuffer,info);
                if (data != null)
                    outputStream.write(data.array());
            }

            outputStream.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSpsPps(byte[] sps, byte[] pps) {
        writeFlvHeader();
        writeMetaData();
        writeFirstVideoTag(sps, pps);
        writeFirstAudioTag();
        startTime = System.currentTimeMillis();
        isHeaderWrite = true;
    }



    private ByteBuffer prepareVideoData(ByteBuffer byteBuffer, MediaCodec.BufferInfo info){
        boolean isKeyFrame = info.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME;
        int packetType = INTER_FRAME;
        if(isKeyFrame) {
            isKeyFrameWrite = true;
            packetType = KEY_FRAME;
        }
        if(!isKeyFrameWrite) {
            return null;
        }

        byte[] video = byteBuffer.array();
        int videoPacketSize = VIDEO_HEADER_SIZE + video.length;
        int dataSize = videoPacketSize + FLV_TAG_HEADER_SIZE;
        int size = dataSize + PRE_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        FlvPackerHelper.writeFlvTagHeader(buffer, FlvPackerHelper.FlvTag.Video, videoPacketSize, (int) info.presentationTimeUs);
        FlvPackerHelper.writeH264Packet(buffer, video, isKeyFrame);
        buffer.putInt(dataSize);
        return buffer;
    }

    private ByteBuffer prepareAudioData(ByteBuffer byteBuffer, MediaCodec.BufferInfo info){
        byteBuffer.position(info.offset);
        byteBuffer.limit(info.offset + info.size);
        byte[] audio = new byte[info.size];
        byteBuffer.get(audio);


        int audioPacketSize = AUDIO_HEADER_SIZE + audio.length;
        int dataSize = audioPacketSize + FLV_TAG_HEADER_SIZE;
        int size = dataSize + PRE_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        FlvPackerHelper.writeFlvTagHeader(buffer, FlvPackerHelper.FlvTag.Audio, audioPacketSize, (int) info.presentationTimeUs);
        FlvPackerHelper.writeAudioTag(buffer, audio, false, audioSampleSize);
        buffer.putInt(dataSize);
        return buffer;
    }

    @Override
    public void stopRecord() {
        status = Status.STOPPED;
        isHeaderWrite = false;
        isKeyFrameWrite = false;
        try {
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }




    private void writeFlvHeader() {
        int size = FLV_HEAD_SIZE + PRE_SIZE;
        ByteBuffer headerBuffer = ByteBuffer.allocate(size);
        FlvPackerHelper.writeFlvHeader(headerBuffer, true, true);
        headerBuffer.putInt(0);
        try {
            outputStream.write(headerBuffer.array());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void writeMetaData() {
        byte[] metaData = FlvPackerHelper.writeFlvMetaData(videoWidth, videoHeight,
                videoFps, audioSampleRate, audioSampleSize, isStereo);
        int dataSize = metaData.length + FLV_TAG_HEADER_SIZE;
        int size = dataSize + PRE_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        FlvPackerHelper.writeFlvTagHeader(buffer, FlvPackerHelper.FlvTag.Script, metaData.length, 0);
        buffer.put(metaData);
        buffer.putInt(dataSize);

        try {
            outputStream.write(buffer.array());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void writeFirstVideoTag(byte[] sps, byte[] pps) {
        int firstPacketSize = VIDEO_HEADER_SIZE + VIDEO_SPECIFIC_CONFIG_EXTEND_SIZE + sps.length + pps.length;
        int dataSize = firstPacketSize + FLV_TAG_HEADER_SIZE;
        int size = dataSize + PRE_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        FlvPackerHelper.writeFlvTagHeader(buffer, FlvPackerHelper.FlvTag.Video, firstPacketSize, 0);
        FlvPackerHelper.writeFirstVideoTag(buffer, sps, pps);
        buffer.putInt(dataSize);

        try {
            outputStream.write(buffer.array());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void writeFirstAudioTag() {
        int firstAudioPacketSize = AUDIO_SPECIFIC_CONFIG_SIZE + AUDIO_HEADER_SIZE;
        int dataSize = FLV_TAG_HEADER_SIZE + firstAudioPacketSize;
        int size = dataSize + PRE_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        FlvPackerHelper.writeFlvTagHeader(buffer, FlvPackerHelper.FlvTag.Audio, firstAudioPacketSize, 0);
        FlvPackerHelper.writeFirstAudioTag(buffer, audioSampleRate, isStereo, audioSampleSize);
        buffer.putInt(dataSize);

        try {
            outputStream.write(buffer.array());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public boolean isPrerecording() {
        return isPrerecording;
    }

    public void setPrerecording(boolean prerecording) {
        isPrerecording = prerecording;
    }

    private class Packet{
        private ByteBuffer data;
        private int track;
        private MediaCodec.BufferInfo info;

        public Packet(ByteBuffer data, int track, MediaCodec.BufferInfo info){
            this.data = data;
            this.track = track;
            this.info = info;
        }
    }
}
