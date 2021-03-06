package com.cl.slack.playaudio.audio;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import com.cl.slack.playaudio.util.BytesTransUtil;
import com.cl.slack.playaudio.util.FileUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by slack
 * on 17/2/8 下午4:36.
 * 合成 视频中的音频 需要先从mp4 分离音频
 *
 * 后期处理 问题很多 !
 * TODO: 播放出来的数据获取的慢，而直接写的视频背景音乐处理很快，直接出现 已经处理完了，但是播放的音乐只播放了1s
 * TODO: 测试时，直接写入mp3的数据，播放速度快了，但是直接写mp4的音频 没有问题
 * TODO： 写入背景音乐，播放出来的速率不对
 * TODO: 不播放出来的，可以写入，但是混合时，两个数据帧的长度不一样，混合失败，数据帧的长度是每一帧可能都不一样，看来需要换混合算法
 * TODO：视频帧 的加入
 */

public class MixAudioInVideo {

    private String srcPath;// mp4 file path
    private static final String OUT_PUT_VIDEO_TRACK_NAME = "output_video.mp4";// test
    private static final String OUT_PUT_AUDIO_TRACK_NAME = "output_audio";

    private AudioDecoder mAudioDecoder;
    private AudioDecoder mBackAudioDecoder;
    private boolean mBackLoop = false;

    private MixListener mMixListener;

    private PlayBackMusic mPlayBackMusic;
    private boolean mixStop = false;

    public MixAudioInVideo(String filePath) {
        srcPath = filePath;

//        extractorMedia();
        extractorAudio();
    }

    public MixAudioInVideo setMixListener(MixListener mMixListener) {
        this.mMixListener = mMixListener;
        return this;
    }

    public MixAudioInVideo playBackMusic(String path){
        if(mPlayBackMusic != null){
            mPlayBackMusic.release();
        }
        mPlayBackMusic = new PlayBackMusic(path);
        mPlayBackMusic.startPlayBackMusic();

        return this;
    }

    public  MixAudioInVideo startMixAudioInVideoWithPlay(){
        mixStop = false;
        if(mPlayBackMusic != null) {
            mPlayBackMusic.setNeedRecodeDataEnable(true);
        }
        initAudioEncoder("with_play");
        mixAudioInVideoWithPlay();
        return this;
    }

    public MixAudioInVideo stop(){
        if(mPlayBackMusic != null) {
            mPlayBackMusic.release();
        }
        mixStop = true;
        return this;
    }

    private void mixAudioInVideoWithPlay() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                writeBackDataOnly();
            }
        }).start();
    }

    /**
     *
     * @param mp3FilePath 需要写入的背景音乐
     * @param loop 背景音乐是否循环（背景音乐短而mp4音频长时需要用到）
     */
    public void startMixAudioInVideoWithoutPlay(String mp3FilePath, boolean loop){
        mBackLoop = loop;
        mBackAudioDecoder = new AudioDecoder(mp3FilePath);
        new Thread(new Runnable() {
            @Override
            public void run() {
                mBackAudioDecoder.startPcmExtractor();
                initAudioEncoder("with_out_play");
                mixData();
            }
        }).start();
    }

    private void initAudioEncoder(String fileName) {

        File test = new File(FileUtil.INSTANCE.getSdcardFileDir(),fileName + ".mp3");
        mAudioEncoder = new AudioEncoder(test.getAbsolutePath());
        mAudioEncoder.prepareEncoder();
    }

    /**
     *
     */
    private void writeBackDataOnly() {

        byte[] src = null;
        byte[] back = null;
        byte[] des = null;

        // 判断条件有些问题
        while (!mixStop ) {

            // write origin data is ok
            src = mAudioDecoder.getPCMData(); // 这种不需要播放的，直接处理的，速度很快
            back = mPlayBackMusic.getBackGroundBytes();
            if(src == null && mAudioDecoder.isPCMExtractorEOS()){
                // finish
                break;
            }
            if(src == null && back == null){
                Log.i("slack","continue writeBackDataOnly...");
                continue;
            }
            // 判断是否需要合成
            if (back == null) {
                Log.i("slack","back null...");
                // 播放结束了，解析数据结束了，循环播放时
                if (!mPlayBackMusic.isPlayingMusic() && mPlayBackMusic.isPCMDataEos() && mBackLoop) {
                    mPlayBackMusic.release();
                    mPlayBackMusic.startPlayBackMusic();
                }
                des = src;
            } else {
                Log.i("slack","back not null...");
                des = BytesTransUtil.INSTANCE.averageMix(back,src);
            }
            // test 写入 mp3
            mAudioEncoder.offerAudioEncoderSyn(des);

        }
        Log.i("slack","finish while...");
        mAudioEncoder.stop();
        if(mMixListener != null){
            mMixListener.onFinished();
        }
    }

    private AudioEncoder mAudioEncoder; // test
    private void mixData() {

        byte[] src = null;
        byte[] back = null;
        byte[] des;
        /**
         * 原视频 还有数据没有读完,或者背景音乐还有数据没有读完
         * 或者没有数据，但是没有读取数据完成，继续循环
         */
        while (src != null || back != null ||
                !mAudioDecoder.isPCMExtractorEOS() || !mBackAudioDecoder.isPCMExtractorEOS()) {

            src = mAudioDecoder.getPCMData();
            back = mBackAudioDecoder.getPCMData();

            if (mixStop || (src == null && mAudioDecoder.isPCMExtractorEOS())) {
                Log.i("slack","end mix write data...");
                // end of the mix
                mBackAudioDecoder.release();
                mAudioEncoder.stop();
                if(mMixListener != null){
                    mMixListener.onFinished();
                }
                break;
            }
            // 防止两个文件都在读取的过程中
            if(src == null && back == null){
                Log.i("slack","continue mix write data...");
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                continue;
            }

            // 判断是否需要合成
            if (back == null) {
                if (mBackAudioDecoder.isPCMExtractorEOS() && mBackLoop) {
                    mBackAudioDecoder.startPcmExtractor();
                }
                des = src;
            } else {
                des = BytesTransUtil.INSTANCE.averageMix(src,back);
            }
//            des = src;// only src data ok
//            des = back; // only back error 写入的数据貌似丢帧了
            // test 写入 mp3
            mAudioEncoder.offerAudioEncoderSyn(des);
            Log.i("slack","mix write data...");
        }
        Log.i("slack","finish while...");
    }

    /**
     * 只分离视频中的音频
     */
    private void extractorAudio() {
        mAudioDecoder = new AudioDecoder(srcPath);
        mAudioDecoder.startPcmExtractor();
    }

    /**
     * 分离视频中的音视频,直接输出到文件
     */
    private void extractorMedia() {
        MediaExtractor mMediaExtractor = null;

        FileOutputStream videoOutputStream = null;
        FileOutputStream audioOutputStream = null;
        try {
            //分离的视频文件
            File videoFile = new File(FileUtil.INSTANCE.getSdcardFileDir(), OUT_PUT_VIDEO_TRACK_NAME);
            //分离的音频文件
            File audioFile = new File(FileUtil.INSTANCE.getSdcardFileDir(), OUT_PUT_AUDIO_TRACK_NAME);
            videoOutputStream = new FileOutputStream(videoFile);
            audioOutputStream = new FileOutputStream(audioFile);
            //源文件
            mMediaExtractor = new MediaExtractor();
            mMediaExtractor.setDataSource(srcPath);
            //信道总数
            int trackCount = mMediaExtractor.getTrackCount();
            int audioTrackIndex = -1;
            int videoTrackIndex = -1;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat trackFormat = mMediaExtractor.getTrackFormat(i);
                String mineType = trackFormat.getString(MediaFormat.KEY_MIME);
                //视频信道
                if (mineType.startsWith("video/")) {
                    videoTrackIndex = i;
                }
                //音频信道
                if (mineType.startsWith("audio/")) {
                    audioTrackIndex = i;
                }
            }

            ByteBuffer byteBuffer = ByteBuffer.allocate(500 * 1024);
            //切换到视频信道
            mMediaExtractor.selectTrack(videoTrackIndex);
            while (true) {
                int readSampleCount = mMediaExtractor.readSampleData(byteBuffer, 0);
                if (readSampleCount < 0) {
                    break;
                }
                //保存视频信道信息
                byte[] buffer = new byte[readSampleCount];
                byteBuffer.get(buffer);
                videoOutputStream.write(buffer);
                byteBuffer.clear();
                mMediaExtractor.advance();
            }
            //切换到音频信道
            mMediaExtractor.selectTrack(audioTrackIndex);
            while (true) {
                int readSampleCount = mMediaExtractor.readSampleData(byteBuffer, 0);
                if (readSampleCount < 0) {
                    break;
                }
                //保存音频信息
                byte[] buffer = new byte[readSampleCount];
                byteBuffer.get(buffer);
                audioOutputStream.write(buffer);
                byteBuffer.clear();
                mMediaExtractor.advance();
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(mMediaExtractor != null) {
                mMediaExtractor.release();
            }
            try {
                if (videoOutputStream != null) {
                    videoOutputStream.close();
                }
                if (audioOutputStream != null) {
                    audioOutputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public interface MixListener{
        void onFinished();
    }
}
