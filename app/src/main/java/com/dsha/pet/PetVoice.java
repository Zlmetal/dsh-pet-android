package com.dsha.pet;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

/**
 * 念工作状态台词。
 *
 * 主路径是**内置音频**：16 句台词事先用 NAS 上的 dsh-voice（sherpa-onnx / VITS，音色
 * theresa）合成好，转成 22050Hz 单声道 vorbis，放在 assets/voice/ 里，一共 210KB 左右。
 * 好处是音色固定、跟网页端一致、不依赖手机装没装中文 TTS 引擎，也不吃流量。
 *
 * 兜底路径是系统 TTS：台词表以后要是加了新句子（没对应音频），或者内置音频放不出来，
 * 就退回 {@link TextToSpeech}，总比哑巴强。
 *
 * 一台设备只留一个播放器：MediaPlayer 反复 new 会互相抢音频焦点。
 */
final class PetVoice {

    private static PetVoice inst;

    /** 台词 → assets 里的音频路径。内容来自 PetService.WORK_TEXTS，组序与文件名一致。 */
    private static final Map<String, String> CLIPS = buildClips();

    private static Map<String, String> buildClips() {
        Map<String, String> m = new HashMap<>();
        String[][] texts = PetService.WORK_TEXTS;
        for (int i = 0; i < texts.length; i++) {
            for (int j = 0; j < texts[i].length; j++) {
                m.put(texts[i][j], "voice/v" + i + "_" + j + ".ogg");
            }
        }
        return m;
    }

    private MediaPlayer player;

    private TextToSpeech tts;
    private boolean ready = false;
    private boolean failed = false;
    private final Queue<String> queue = new ArrayDeque<>();

    private String lastText;
    private long lastAt;

    private PetVoice() {
    }

    static PetVoice get() {
        if (inst == null) inst = new PetVoice();
        return inst;
    }

    /** 提前准备，别等第一条台词到了才现开（首句会延迟）。内置音频不用预热，只清 TTS 的失败态。 */
    void warm(Context c) {
        // 内置音频是现开现播的，冷启动也就几十毫秒，这里什么都不用做。
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /**
     * 念一句。
     *
     * 同一句话 3 秒内不念第二遍——一轮任务结束时 tend 会连报两次状态，听两遍很吵。
     */
    void speak(Context c, String text) {
        if (text == null || text.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (text.equals(lastText) && now - lastAt < 3000) return;
        lastText = text;
        lastAt = now;

        String clip = CLIPS.get(text);
        if (clip != null && playClip(c.getApplicationContext(), clip, text)) return;
        speakTts(c.getApplicationContext(), text);
    }

    void stop() {
        stopClip();
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    void release() {
        ready = false;
        queue.clear();
        stopClip();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
        }
        tts = null;
        inst = null;
    }

    // ------------------------------------------------------------------
    // 内置音频
    // ------------------------------------------------------------------

    /** 返回 true 表示已经交给播放器了；false 表示放不出来，调用方去走 TTS。 */
    private boolean playClip(Context c, String asset, String text) {
        stopClip();
        try {
            MediaPlayer mp = new MediaPlayer();
            // 走媒体流：系统 TTS 默认就在媒体流上，换播放器不能把声音换到通知流去，
            // 否则用户静音通知就听不见了（以前 TTS 是听得见的）。
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            AssetFileDescriptor afd = openAsset(c, asset);
            try {
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                mp.prepare();
            } finally {
                try {
                    afd.close();
                } catch (Throwable ignored) {
                }
            }
            mp.setOnCompletionListener(p -> stopClip());
            mp.setOnErrorListener((p, what, extra) -> {
                stopClip();
                speakTts(c, text);
                return true;
            });
            mp.start();
            player = mp;
            android.util.Log.i("PetVoice", "clip ok " + asset + " " + text);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("PetVoice", "clip fail " + asset + " " + t);
            stopClip();
            return false;
        }
    }

    /**
     * 拿 assets 里音频的文件描述符。
     *
     * aapt 默认不压缩 .ogg，正常情况下直接 openFd 就行；万一哪天被压了（openFd 抛
     * 「This file can not be opened as a file descriptor」），就落一份到 cache 再播。
     */
    private AssetFileDescriptor openAsset(Context c, String asset) throws Exception {
        try {
            return c.getAssets().openFd(asset);
        } catch (java.io.IOException compressed) {
            File dir = new File(c.getCacheDir(), "voice");
            if (!dir.exists() && !dir.mkdirs()) throw compressed;
            File f = new File(dir, asset.replace('/', '_'));
            if (!f.exists() || f.length() == 0) {
                File tmp = new File(f.getAbsolutePath() + ".tmp");
                InputStream in = c.getAssets().open(asset);
                try {
                    FileOutputStream out = new FileOutputStream(tmp);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.flush();
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
                if (!tmp.renameTo(f)) throw compressed;
            }
            return new AssetFileDescriptor(
                    android.os.ParcelFileDescriptor.open(f,
                            android.os.ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length());
        }
    }

    private void stopClip() {
        final MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            if (mp.isPlaying()) mp.stop();
        } catch (Throwable ignored) {
        }
        try {
            mp.release();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 系统 TTS（兜底）
    // ------------------------------------------------------------------

    private void speakTts(Context c, String text) {
        android.util.Log.i("PetVoice", "tts fallback " + text);
        ensure(c);
        if (failed) return;
        if (ready) utter(text);
        else if (queue.size() < 4) queue.add(text);
    }

    private void ensure(Context c) {
        if (tts != null || failed) return;
        try {
            tts = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) {
                        failed = true;
                        queue.clear();
                        return;
                    }
                    applyLang();
                    ready = true;
                    while (!queue.isEmpty()) utter(queue.poll());
                }
            });
        } catch (Throwable t) {
            failed = true;
            tts = null;
        }
    }

    /** 中文优先；没有中文包就退回系统默认，总比不念强。 */
    private void applyLang() {
        try {
            int r = tts.setLanguage(Locale.CHINA);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setSpeechRate(1.0f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                }

                @Override
                public void onError(String id) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void utter(String text) {
        try {
            // 打断上一条：攒着的旧状态念出来没意义，只念最新的
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pet" + System.nanoTime());
        } catch (Throwable ignored) {
        }
    }
}
