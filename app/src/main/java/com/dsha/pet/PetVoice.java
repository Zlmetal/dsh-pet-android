package com.dsha.pet;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

/**
 * 用手机自带的 TTS 念工作状态台词。
 *
 * 走系统引擎（Y700 上是 com.oplus.ttsaccessibilityengine），离线、不花流量、不用额
 * 外依赖。引擎的初始化是异步的，初始化没完就来的台词先排队，ready 之后一口气念出去。
 *
 * 一台设备只留一个实例：TTS 引擎是系统级资源，反复 new 会互相抢焦点。
 */
final class PetVoice {

    private static PetVoice inst;

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

    /** 提前初始化，别等第一条台词到了才现开引擎（首句会延迟一两秒）。 */
    void warm(Context c) {
        ensure(c.getApplicationContext());
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

    /**
     * 念一句。
     *
     * 同一句话 3 秒内不念第二遍——一轮任务结束时 tend 会连报两次状态，听两遍很吵。
     * 引擎没就绪就排队；彻底没引擎（failed）就当没这回事，不抛也不提示。
     */
    void speak(Context c, String text) {
        if (text == null || text.isEmpty()) return;
        ensure(c.getApplicationContext());
        if (failed) return;
        long now = System.currentTimeMillis();
        if (text.equals(lastText) && now - lastAt < 3000) return;
        lastText = text;
        lastAt = now;
        if (ready) utter(text);
        else if (queue.size() < 4) queue.add(text);
    }

    private void utter(String text) {
        try {
            // 打断上一条：攒着的旧状态念出来没意义，只念最新的
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pet" + System.nanoTime());
        } catch (Throwable ignored) {
        }
    }

    void stop() {
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    void release() {
        ready = false;
        queue.clear();
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
}
