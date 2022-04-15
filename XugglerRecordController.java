package com.pedro.rtpstreamer;


import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Process;

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
import com.xuggle.xuggler.io.XugglerIO;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

public class XugglerRecordController extends BaseRecordController {

   private IContainer container;
   private IStreamCoder videoEncoder;
   private IStreamCoder audioEncoder;
   private IRational timebase = IRational.make(1, 1000 * 1000);
   private String outputUrl;
   private int audioSampleRate;
   private int audioBitrate;
   private int channels;
   private int videoBitrate;
   private int width;
   private int height;
   private IContainerFormat format = IContainerFormat.make();
   private CompletionService<Void> exececutor = new ExecutorCompletionService(
           Executors.newSingleThreadExecutor(runnable -> {
              Thread thread = new Thread(runnable);
              Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
              return thread;
           })
   );
   private ConcurrentLinkedQueue queue = new ConcurrentLinkedQueue<IPacket>();


   private PipedInputStream pipedInputStream;
   private PipedOutputStream pipedOutputStream;
   private FileOutputStream fileOutputStream;

   public XugglerRecordController(int width, int height, int videoBitrate,
                                  int audioSampleRate, int audioBitrate, int channels) {
      this.width = height;
      this.height = width;
      this.videoBitrate = videoBitrate;
      this.audioSampleRate = audioSampleRate;
      this.audioBitrate = audioBitrate;
      this.channels = channels;
      format.setOutputFormat("mp4", null, null);
   }

   @Override
   public void startRecord(@NonNull String path, @Nullable Listener listener) throws IOException {
      pipedInputStream = new PipedInputStream();
      pipedOutputStream = new PipedOutputStream(pipedInputStream);
      fileOutputStream = new FileOutputStream(path);
      outputUrl = Uri.parse(XugglerIO.map(pipedOutputStream)).toString();
      outputUrl = path;
      status = Status.STARTED;
      if (listener != null)
         listener.onStatusChange(status);
      init();
   }

   @Override
   public void startRecord(@NonNull FileDescriptor fd, @Nullable Listener listener) throws IOException {
      throw new IOException("NON IMPLEMENTED");
   }


   public void init() {
      container = IContainer.make();
      container.open(outputUrl, IContainer.Type.WRITE, format);
      container.setStandardsCompliance(IStreamCoder.CodecStandardsCompliance.COMPLIANCE_STRICT);
        container.setProperty("movflags", "frag_keyframe+empty_moov+faststart");

      ICodec videoCodec = ICodec.findEncodingCodec(ICodec.ID.AV_CODEC_ID_H264);
      videoEncoder = IStreamCoder.make(IStreamCoder.Direction.ENCODING, videoCodec);
      container.addNewStream(videoEncoder);
      videoEncoder.setBitRate(videoBitrate);
      videoEncoder.setPixelType(IPixelFormat.Type.YUV420P);
      videoEncoder.setHeight(height);
      videoEncoder.setWidth(width);
      videoEncoder.setNumPicturesInGroupOfPictures(60);
      IRational frameRate = IRational.make(1, 30);
      videoEncoder.setFrameRate(frameRate);
      videoEncoder.setTimeBase(timebase);
      videoTrack = 0;
      videoEncoder.open();

      ICodec audioCodec = ICodec.findEncodingCodec(ICodec.ID.AV_CODEC_ID_AAC);
      audioEncoder = IStreamCoder.make(IStreamCoder.Direction.ENCODING, audioCodec);
      container.addNewStream(audioEncoder);
      audioEncoder.setSampleRate(audioSampleRate);
      audioEncoder.setSampleFormat(IAudioSamples.Format.FMT_FLTP);
      audioEncoder.setTimeBase(timebase);
      audioEncoder.setBitRate(audioBitrate);
      audioEncoder.setChannels(channels);
      audioEncoder.setProperty("ch_mode", "indep");
      audioTrack = 1;
      audioEncoder.open();

      container.writeHeader();

      status = Status.RECORDING;
      if (listener != null) listener.onStatusChange(status);

      Executors.newSingleThreadExecutor().execute(() -> {
         try{
            byte[] buffer = new byte[4096];
            while (status == Status.RECORDING) {
               int read = 0;
               while ((read = pipedInputStream.read(buffer)) != -1) {
                  fileOutputStream.write(buffer, 0, read);
               }
            }
         }
         catch (Throwable t){

         }
      });


      exececutor.submit(() -> {
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

      }, null);

   }

   @Override
   public void recordVideo(ByteBuffer videoBuffer, MediaCodec.BufferInfo videoInfo) {
      if (status == Status.RESUMED && (videoInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME
              || isKeyFrame(videoBuffer))) {
         status = Status.RECORDING;
         if (listener != null) listener.onStatusChange(status);
      }

      if (status == Status.RECORDING) {

         updateFormat(this.videoInfo, videoInfo);
         write(videoTrack, videoBuffer, this.videoInfo);
      }
   }

   @Override
   public void recordAudio(ByteBuffer audioBuffer, MediaCodec.BufferInfo audioInfo) {
      if (status == Status.RECORDING) {
         if (videoInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME)
            return;

         updateFormat(this.audioInfo, audioInfo);
         write(audioTrack, audioBuffer, this.audioInfo);
      }
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


   public void write(int track, ByteBuffer byteBuffer, MediaCodec.BufferInfo info) {
      IPacket packet = IPacket.make();

      packet.setData(IBuffer.make(null, byteBuffer, 0, info.size));
      packet.setTimeStamp(info.presentationTimeUs);
      packet.setTimeBase(timebase);
      packet.setStreamIndex(track);


      if (packet.isComplete()) {
         queue.offer(packet);
      }



   }


   @Override
   public void stopRecord() {
      status = Status.STOPPED;
      pauseMoment = 0;
      pauseTime = 0;
      if (listener != null) listener.onStatusChange(status);
   }


}

