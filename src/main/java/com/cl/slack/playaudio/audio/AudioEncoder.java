package com.cl.slack.playaudio.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by slack
 * on 17/2/6 下午12:26.
 * 对音频数据进行编码
 * MediaCodec & MediaMuxer write data
 */
public class AudioEncoder {
    private static final String TAG = "AudioEncoder";
    //编码
    private MediaCodec mAudioCodec;     //音频编解码器
    private MediaFormat mAudioFormat;
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm"; //音频类型
    private static final int SAMPLE_RATE = 44100; //采样率(CD音质)
    private TrackIndex mAudioTrackIndex = new TrackIndex();
    private MediaMuxer mMediaMuxer;     //混合器
    private boolean mMuxerStart = false; //混合器启动的标志
    private MediaCodec.BufferInfo mAudioBufferInfo;
    private static long audioBytesReceived = 0;        //接收到的音频数据 用来设置录音起始时间的
    private long audioStartTime;
    private String recordFile;
    private boolean eosReceived = false;  //终止录音的标志
    private ExecutorService encodingService = Executors.newSingleThreadExecutor(); //序列化线程任务

    private long mLastAudioPresentationTimeUs = 0;

    //枚举值 一个用来标志编码 一个标志编码完成
    private enum EncoderTaskType {
        ENCODE_FRAME, FINALIZE_ENCODER
    }

    public AudioEncoder(String filePath) {
        recordFile = filePath;
//        prepareEncoder();
    }

    private class TrackIndex {
        int index = 0;
    }

    private Callback mCallback;
    public void setAudioEncodeCallback(Callback callback){
        mCallback = callback;
    }

    public void prepareEncoder() {
        eosReceived = false;
        audioBytesReceived = 0;
        mAudioBufferInfo = new MediaCodec.BufferInfo();
        mAudioFormat = new MediaFormat();
        mAudioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mAudioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
        mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        mAudioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);
        mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 10 * 1024);
        try {
            mAudioCodec = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
            mAudioCodec.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioCodec.start();
            mMediaMuxer = new MediaMuxer(recordFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Log.d(TAG, "prepareEncoder...");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void prepareEncoder(MediaFormat format) {
        if(format == null){
            this.prepareEncoder();
            return;
        }
        eosReceived = false;
        audioBytesReceived = 0;
        mAudioBufferInfo = new MediaCodec.BufferInfo();
        mAudioFormat = new MediaFormat();
        mAudioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
        mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE));
        mAudioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
        mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mAudioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 10 * 1024);
        try {
            mAudioCodec = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
            mAudioCodec.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioCodec.start();
            mMediaMuxer = new MediaMuxer(recordFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Log.d(TAG, "prepareEncoder...");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    //此方法 由AudioRecorder任务调用 开启编码任务
    public void offerAudioEncoder(byte[] input, long presentationTimeStampNs) {
        if (!encodingService.isShutdown()) {
//            Log.d(TAG, "encodingServiceEncoding--submit: " + input.length + "  " + presentationTimeStampNs) ;
            encodingService.submit(new AudioEncodeTask(this, input, presentationTimeStampNs));
        }

    }

    /**
     * 同步操作
     */
    void offerAudioEncoderSyn(byte[] input) {
        _offerAudioEncoder(input, 0);
    }

    /**
     * 异步操作 适合录制时调用
     */
    public void offerAudioEncoder(ByteBuffer buffer, long presentationTimeStampNs, int length) {
        if (!encodingService.isShutdown()) {
            encodingService.submit(new AudioEncodeTask(this, buffer, length, presentationTimeStampNs));
        }

    }

    //发送音频数据和时间进行编码
    private void _offerAudioEncoder(byte[] input, long pts) {
        if (audioBytesReceived == 0) {
            audioStartTime = System.nanoTime();
        }
        if(input != null) {
            audioBytesReceived += input.length;
        }
        drainEncoder(mAudioCodec, mAudioBufferInfo, mAudioTrackIndex, false);
        try {
            ByteBuffer[] inputBuffers = mAudioCodec.getInputBuffers();
            int inputBufferIndex = mAudioCodec.dequeueInputBuffer(-1);
//            Log.d(TAG, "inputBufferIndex--" + inputBufferIndex);
            if (inputBufferIndex >= 0) {
                if(input != null) {
                    ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                    inputBuffer.clear();
                    inputBuffer.put(input);
                }

                //录音时长
                long presentationTimeUs = (System.nanoTime() - audioStartTime) / 1000L;
//                Log.d(TAG, "presentationTimeUs--" + presentationTimeUs);
                if (eosReceived) {
                    mAudioCodec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    finishMuxer();
                } else if(input != null){
                    mAudioCodec.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, 0);
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "_offerAudioEncoder exception " + t.getMessage());
        }

    }

    private void finishMuxer() {
        closeEncoder(mAudioCodec, mAudioBufferInfo, mAudioTrackIndex);
        closeMuxer();
        encodingService.shutdown();
        if(mCallback != null){
            mCallback.onDecodeFinish();
        }
    }

    private void _offerAudioEncoder(ByteBuffer buffer, int length, long pts) {
        if (audioBytesReceived == 0) {
            audioStartTime = pts;
        }
        audioBytesReceived += length;
        drainEncoder(mAudioCodec, mAudioBufferInfo, mAudioTrackIndex, false);
        try {
            ByteBuffer[] inputBuffers = mAudioCodec.getInputBuffers();
            int inputBufferIndex = mAudioCodec.dequeueInputBuffer(-1);
//            Log.d(TAG, "inputBufferIndex--" + inputBufferIndex);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                if (buffer != null) {
                    inputBuffer.put(buffer);
                }

                //录音时长
                long presentationTimeUs = (pts - audioStartTime) / 1000;
//                Log.d(TAG, "presentationTimeUs--" + presentationTimeUs);
                if (eosReceived) {
                    mAudioCodec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    finishMuxer();

                } else {
                    mAudioCodec.queueInputBuffer(inputBufferIndex, 0, length, presentationTimeUs, 0);
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "_offerAudioEncoder exception " + t.getMessage());
        }

    }

    /**
     * try 修复 E/MPEG4Writer: timestampUs 6220411 < lastTimestampUs 6220442 for Audio track
     * add check : mLastAudioPresentationTimeUs < bufferInfo.presentationTimeUs
     */
    private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex, boolean endOfStream) {
        final int TIMEOUT_USEC = 100;
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        try {
            while (true) {
                int encoderIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
//                Log.d(TAG, "encoderIndex---" + encoderIndex);
                if (encoderIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    //没有可进行混合的输出流数据 但还没有结束录音 此时退出循环
//                    Log.d(TAG, "info_try_again_later");
                    if (!endOfStream)
                        break;
                    else
                        Log.d(TAG, "no output available, spinning to await EOS");
                } else if (encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    //只会在第一次接收数据前 调用一次
                    if (mMuxerStart)
                        throw new RuntimeException("format 在muxer启动后发生了改变");
                    MediaFormat newFormat = encoder.getOutputFormat();
                    trackIndex.index = mMediaMuxer.addTrack(newFormat);
                    if (!mMuxerStart) {
                        mMediaMuxer.start();
                    }
                    mMuxerStart = true;
                } else if (encoderIndex < 0) {
                    Log.w(TAG, "encoderIndex 非法" + encoderIndex);
                } else {
                    //退出循环
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }

                    ByteBuffer encodeData = encoderOutputBuffers[encoderIndex];
                    if (encodeData == null) {
                        throw new RuntimeException("编码数据为空");
                    }else
                    if (bufferInfo.size != 0 && mLastAudioPresentationTimeUs < bufferInfo.presentationTimeUs) {
                        if (!mMuxerStart) {
                            throw new RuntimeException("混合器未开启");
                        }
                        Log.d(TAG, "write_info_data......");
                        encodeData.position(bufferInfo.offset);
                        encodeData.limit(bufferInfo.offset + bufferInfo.size);
//                        Log.d(TAG, "presentationTimeUs--bufferInfo : " + bufferInfo.presentationTimeUs);
                        mMediaMuxer.writeSampleData(trackIndex.index, encodeData, bufferInfo);

                        mLastAudioPresentationTimeUs = bufferInfo.presentationTimeUs;
                    }

                    encoder.releaseOutputBuffer(encoderIndex, false);

                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "error :: " + e.getMessage());
        }

    }

    /**
     * 关闭编码
     */
    private void closeEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackIndex trackIndex) {
        drainEncoder(encoder, bufferInfo, trackIndex, true);
        encoder.stop();
        encoder.release();
    }

    /**
     * 关闭混合器
     */
    private void closeMuxer() {
        if (mMuxerStart && mMediaMuxer != null) {
            mMediaMuxer.stop();
            mMediaMuxer.release();
            mMediaMuxer = null;
            mMuxerStart = false;
        }
    }

    //发送终止编码信息
    public void stop() {
        if (!encodingService.isShutdown()) {
            encodingService.submit(new AudioEncodeTask(this, EncoderTaskType.FINALIZE_ENCODER));
        }
    }

    //终止编码
    private void _stop() {
        eosReceived = true;
        offerAudioEncoderSyn(null);
        Log.d(TAG, "停止编码");
    }


    /**
     * 音频编码任务
     */
    private class AudioEncodeTask implements Runnable {
        private static final String TAG = "AudioEncoderTask";
        private boolean is_initialized = false;
        private AudioEncoder encoder;
        private byte[] audio_data;
        private ByteBuffer byteBuffer;
        private int length;
        long pts;
        private EncoderTaskType type;

        //进行编码任务时 调用此构造方法
        private AudioEncodeTask(AudioEncoder encoder, byte[] audio_data, long pts) {
            this.encoder = encoder;
            this.audio_data = audio_data;
            this.pts = pts;
            is_initialized = true;
            this.type = EncoderTaskType.ENCODE_FRAME;
            //这里是有数据的
//            Log.d(TAG,"AudioData--"+audio_data + " pts--"+pts);
        }

        private AudioEncodeTask(AudioEncoder encoder, ByteBuffer buffer, int length, long pts) {
            this.encoder = encoder;
            this.byteBuffer = buffer;
            this.length = length;
            this.pts = pts;
            is_initialized = true;
            this.type = EncoderTaskType.ENCODE_FRAME;
            //这里是有数据的
        }

        //当要停止编码任务时 调用此构造方法
        private AudioEncodeTask(AudioEncoder encoder, EncoderTaskType type) {
            this.type = type;

            if (type == EncoderTaskType.FINALIZE_ENCODER) {
                this.encoder = encoder;
                is_initialized = true;
            }
//            Log.d(TAG, "完成...");
        }

        ////编码
        private void encodeFrame() {
//            Log.d(TAG, "audio_data---encoder--" + audio_data);
            if (audio_data != null && encoder != null) {
                encoder._offerAudioEncoder(audio_data, pts);
                audio_data = null;
            } else if (byteBuffer != null && encoder != null) {
                encoder._offerAudioEncoder(byteBuffer, length, pts);
                byteBuffer.clear();
            }

        }

        //终止编码
        private void finalizeEncoder() {
            encoder._stop();
        }

        @Override
        public void run() {
            Log.d(TAG, "is_initialized--" + is_initialized + " " + type);
            if (is_initialized) {
                switch (type) {
                    case ENCODE_FRAME:
                        //进行编码
                        encodeFrame();
                        break;
                    case FINALIZE_ENCODER:
                        //完成编码
                        finalizeEncoder();
                        break;
                }
                is_initialized = false;
            } else {
                //打印错误日志
                Log.e(TAG, "AudioEncoderTask is not initiallized");
            }
        }
    }


    public interface Callback{
        void onDecodeFinish();
        void onProgress(int current,int total);
    }
}

