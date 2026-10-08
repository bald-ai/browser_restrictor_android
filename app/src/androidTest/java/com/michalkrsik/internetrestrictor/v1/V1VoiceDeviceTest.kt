package com.michalkrsik.internetrestrictor.v1

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.michalkrsik.internetrestrictor.appContainer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Paid, opt-in, actual GPT-Live audio test. Synthetic microphone wiring exists only in this test. */
class V1VoiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val coordinator get() = context.appContainer.v1
    private val repository get() = context.appContainer.repository

    @Test fun recordedSpeechReviewButtonAndNativeAck() = runBlocking {
        assumeTrue("Set -e v1VoiceSmoke true to run the paid Live test", InstrumentationRegistry.getArguments().getString("v1VoiceSmoke") == "true")

        assertTrue(context.packageName.endsWith(".v1dev"))
        val audioFile = File(context.filesDir, "v1-voice-request.wav")
        assertTrue("Push the 'Please set my waiting time to five seconds' WAV first", audioFile.exists())
        assertTrue(coordinator.sync())
        val initial = coordinator.api.request("/config")
        val originalWait = initial.getJSONObject("config").getJSONObject("settings").getLong("waitSeconds")
        var scenario: ActivityScenario<V1ConversationActivity>? = null
        var web: WebView? = null
        val report = JSONObject().put("input", "WAV audio: Please set my waiting time to five seconds")
        var beforeIds = emptySet<String>()
        var cleanupArmed = false
        try {
            if (originalWait == 5L) setWait(12)
            val startWait = repository.snapshot.value.configuration.settings.waitSeconds
            val before = coordinator.api.request("/state")
            beforeIds = before.getJSONArray("proposals").objects().map { it.getString("id") }.toSet()
            cleanupArmed = true
            scenario = ActivityScenario.launch(V1ConversationActivity::class.java)
            await("native trusted WebView loaded", 35_000) {
                scenario!!.onActivity { activity -> web = findWebView(activity.window.decorView) }
                web != null && evaluate(web!!, "document.getElementById('connection')?.textContent || ''").contains("Connected")
            }
            val view = web!!
            val wav = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
            evaluate(view, """
                (() => {
                  const test = window.__v1AudioTest = {events:[], transitions:[], browserErrors:[], speechEndedAt:0, error:null};
                  window.addEventListener('error', event=>test.browserErrors.push({at:Date.now(),type:'error',message:event.message}));
                  window.addEventListener('unhandledrejection', event=>test.browserErrors.push({at:Date.now(),type:'rejection',message:String(event.reason?.message||event.reason)}));
                  const originalPlay = HTMLMediaElement.prototype.play;
                  HTMLMediaElement.prototype.play = function(...args) {
                    test.outputAudio = this;
                    return originalPlay.apply(this,args).catch(error=>{
                      test.playbackError={name:error.name,message:error.message};
                      test.events.push({type:'client.playback.error',name:error.name,message:error.message,_observedAt:Date.now()});
                      throw error;
                    });
                  };
                  const Original = window.RTCPeerConnection;
                  window.RTCPeerConnection = class extends Original {
                    constructor(...args) {
                      super(...args); test.peer = this;
                      for(const type of ['connectionstatechange','iceconnectionstatechange','signalingstatechange']) this.addEventListener(type,()=>test.transitions.push({at:Date.now(),type,connectionState:this.connectionState,iceConnectionState:this.iceConnectionState,signalingState:this.signalingState}));
                    }
                    createDataChannel(...args) {
                      const channel = super.createDataChannel(...args);
                      test.channel = channel;
                      for(const type of ['open','close','error']) channel.addEventListener(type,event=>test.transitions.push({at:Date.now(),type:'datachannel.'+type,readyState:channel.readyState,error:event.error?.message||null}));
                      channel.addEventListener('message', event => {
                        try { const data=JSON.parse(event.data); test.events.push({...data,_observedAt:Date.now()}); } catch {}
                      });
                      return channel;
                    }
                  };
                  const context = test.context = new AudioContext({sampleRate:24000});
                  const destination = context.createMediaStreamDestination();
                  const clock = context.createConstantSource(); clock.offset.value=0.00001;
                  clock.connect(destination); clock.start(); test.clock=clock;
                  navigator.mediaDevices.getUserMedia = async () => destination.stream;
                  const bytes=Uint8Array.from(atob('$wav'), c=>c.charCodeAt(0));
                  context.decodeAudioData(bytes.buffer).then(buffer=>{test.buffer=buffer;test.ready=true}).catch(e=>test.error=e.message);
                  test.play = async () => { await context.resume(); const source=context.createBufferSource(); source.buffer=test.buffer;source.connect(destination);source.onended=()=>test.speechEndedAt=Date.now();test.speechStartedAt=Date.now();source.start(); };
                  return 'injected';
                })()
            """.trimIndent())
            await("audio decoded", 8_000) { evaluate(view, "Boolean(window.__v1AudioTest?.ready)") == "true" }
            evaluate(view, "document.getElementById('voice').click(); 'voice clicked'")
            await("GPT-Live WebRTC ready", 50_000) { evaluate(view, "document.getElementById('voice').classList.contains('live')") == "true" }
            Thread.sleep(1_500) // Let the initial context advance through the continuous media clock.
            evaluate(view, "window.__v1AudioTest.play(); 'playing real audio'")
            val start = System.currentTimeMillis()
            var proposal: JSONObject? = null
            await("audio request produced waiting-time review card", 100_000) {
                val state = runBlocking { coordinator.api.request("/state") }
                proposal = state.getJSONArray("proposals").objects().lastOrNull { p ->
                    p.getString("id") !in beforeIds && p.optString("status") == "pending" &&
                        p.getJSONArray("cards").objects().any { it.optString("key") == "waitSeconds" && it.optLong("after", -1) == 5L }
                }
                proposal != null
            }
            report.put("requestToReviewMs", System.currentTimeMillis() - start)
            assertEquals("Saving requires approval", startWait, repository.snapshot.value.configuration.settings.waitSeconds)
            await("native review button visible and enabled", 8_000) { evaluate(view, "Boolean(document.getElementById('applyChanges') && !document.getElementById('applyChanges').disabled)") == "true" }
            assertEquals("Call remains live during review", "true", evaluate(view, "document.getElementById('voice').classList.contains('live')"))
            await("remote audio is playing without a media error", 15_000) {
                evaluate(view, "Boolean(window.__v1AudioTest.outputAudio && !window.__v1AudioTest.outputAudio.paused && window.__v1AudioTest.outputAudio.readyState>=2 && !window.__v1AudioTest.outputAudio.error && window.__v1AudioTest.outputAudio.currentTime>0.1)") == "true"
            }
            val playbackTime = evaluate(view, "window.__v1AudioTest.outputAudio.currentTime").toDouble()
            await("output playback clock advances", 5_000) { evaluate(view, "window.__v1AudioTest.outputAudio.currentTime").toDouble() > playbackTime + 0.1 }
            evaluate(view, """
                window.__v1AudioTest.peer.getStats().then(stats=>{
                  window.__v1AudioTest.inboundAudio=Array.from(stats.values()).filter(s=>s.type==='inbound-rtp'&&(s.kind==='audio'||s.mediaType==='audio')).map(s=>({packetsReceived:s.packetsReceived,bytesReceived:s.bytesReceived,totalAudioEnergy:s.totalAudioEnergy,totalSamplesReceived:s.totalSamplesReceived,audioLevel:s.audioLevel}));
                  window.__v1AudioTest.statsReady=true;
                }).catch(error=>window.__v1AudioTest.statsError=error.message); 'collecting audio stats'
            """.trimIndent())
            await("inbound audio statistics available", 5_000) { evaluate(view, "Boolean(window.__v1AudioTest.statsReady)") == "true" }
            val inboundAudio = JSONArray(decodeJavascriptString(evaluate(view, "JSON.stringify(window.__v1AudioTest.inboundAudio)")))
            assertTrue("Actual remote audio packets received", inboundAudio.objects().any { it.optLong("packetsReceived") > 0 })
            assertTrue("Remote audio contains non-silent output", inboundAudio.objects().any { it.optDouble("totalAudioEnergy", 0.0) > 0.0 })
            report.put("audioPlaybackVerified", true).put("inboundAudio", inboundAudio)
            val confirmedAt = System.currentTimeMillis()
            evaluate(view, "document.getElementById('applyChanges').click(); 'approved via button'")
            await("Android persisted and acknowledged five seconds", 25_000) {
                val state = runBlocking { coordinator.api.request("/state") }
                repository.snapshot.value.configuration.settings.waitSeconds == 5L && state.getLong("revision") == state.getLong("appliedRevision") && state.getJSONObject("config").getJSONObject("settings").getLong("waitSeconds") == 5L
            }
            report.put("buttonToNativeAppliedMs", System.currentTimeMillis() - confirmedAt)
            assertEquals("Button confirmation does not end the voice call", "true", evaluate(view, "document.getElementById('voice').classList.contains('live')"))
            await("undo button visible", 8_000) { evaluate(view, "Boolean(document.getElementById('undoChanges'))") == "true" }
            evaluate(view, "document.getElementById('undoChanges').click(); 'undo via button'")
            await("undo restored prior value and Android acknowledgement", 25_000) {
                val state = runBlocking { coordinator.api.request("/state") }
                repository.snapshot.value.configuration.settings.waitSeconds == startWait && state.getLong("revision") == state.getLong("appliedRevision")
            }
            val eventsJson = decodeJavascriptString(evaluate(view, "JSON.stringify(window.__v1AudioTest.events.filter(e => /transcript|session.started/.test(e.type)))"))
            val events = JSONArray(eventsJson)
            val transcript = events.objects().filter { it.optString("type") == "session.input_transcript.delta" }.joinToString("") { it.optString("delta") }
            assertTrue("Live transcribed actual audio", transcript.contains("five", ignoreCase = true) || transcript.contains("5"))
            report.put("inputTranscript", transcript).put("liveEvents", events).put("appliedAndUndone", true)
                .put("originalWait", originalWait).put("voice", "ripple").put("agent", "gpt-6.1-sol low fast")
            evaluate(view, "window.FrictionStopVoice(); 'stopping'")
            await("call closed", 10_000) { evaluate(view, "document.getElementById('voice').classList.contains('live')") == "false" }
        } catch (failure: Throwable) {
            report.put("failure", JSONObject().put("type", failure.javaClass.simpleName).put("message", failure.message).put("stack", failure.stackTraceToString()))
            throw failure
        } finally {
            // Capture before calling any cleanup: closing the peer here would hide the original failure.
            web?.let { view -> runCatching {
                val raw = decodeJavascriptString(evaluate(view, """
                    JSON.stringify((()=>{
                      const test=window.__v1AudioTest||{}, peer=test.peer, audio=test.outputAudio;
                      return {capturedAt:Date.now(), events:test.events||[], transitions:test.transitions||[], browserErrors:test.browserErrors||[],
                        connectionState:peer?.connectionState||null, iceConnectionState:peer?.iceConnectionState||null,
                        signalingState:peer?.signalingState||null, dataChannelState:test.channel?.readyState||null,
                        audioContextState:test.context?.state||null, inputError:test.error||null, playbackError:test.playbackError||null,
                        inboundAudio:test.inboundAudio||null,statsError:test.statsError||null,
                        speechStartedAt:test.speechStartedAt||null, speechEndedAt:test.speechEndedAt||null,
                        voiceStatus:document.getElementById('voiceStatus')?.textContent||null,
                        voiceLabel:document.getElementById('voiceLabel')?.textContent||null,
                        voiceClass:document.getElementById('voice')?.className||null,
                        visibleAlert:document.getElementById('alert')?.hidden ? null : document.getElementById('alert')?.textContent,
                        outputAudio:audio?{paused:audio.paused,readyState:audio.readyState,networkState:audio.networkState,currentTime:audio.currentTime,muted:audio.muted,error:audio.error?{code:audio.error.code,message:audio.error.message}:null}:null};
                    })())
                """.trimIndent()))
                report.put("beforeCleanup", JSONObject(raw))
            }.onFailure { report.put("diagnosticError", it.message) } }
            File(context.filesDir, "v1-voice-device-result.json").writeText(report.toString(2))
            web?.let { view -> runCatching {
                evaluate(view, "Promise.resolve(window.FrictionStopVoice?.()).finally(()=>window.__v1VoiceClosed=true);window.__v1AudioTest?.clock?.stop();window.__v1AudioTest?.context?.close(); 'cleanup'")
                await("voice cleanup", 8_000) { evaluate(view, "Boolean(window.__v1VoiceClosed)") == "true" }
            } }
            scenario?.close()
            if (cleanupArmed) runCatching {
                val state = coordinator.api.request("/state")
                val pending = state.getJSONArray("proposals").objects().lastOrNull { it.getString("id") !in beforeIds && it.optString("status") == "pending" }
                if (pending != null) coordinator.api.request("/cancel", JSONObject().put("proposalId", pending.getString("id")))
                else if (state.optBoolean("busy")) coordinator.api.request("/cancel", JSONObject())
            }
            runCatching { setWait(originalWait) }.onFailure { report.put("restoreError", it.message) }
            report.put("finalWait", repository.snapshot.value.configuration.settings.waitSeconds)
            File(context.filesDir, "v1-voice-device-result.json").writeText(report.toString(2))
        }
        assertEquals("Original setting restored after experiment", originalWait, repository.snapshot.value.configuration.settings.waitSeconds)
    }

    @Test fun spokenCancellationDiscardsWorkWhileCallStaysOpen() = runBlocking<Unit> {
        assumeTrue("Paid opt-in voice test", InstrumentationRegistry.getArguments().getString("v1VoiceSmoke") == "true")
        assertTrue(context.packageName.endsWith(".v1dev"))
        assertTrue(coordinator.sync())
        val originalWait = repository.snapshot.value.configuration.settings.waitSeconds
        val requestAudio = File(context.filesDir, "v1-voice-request.wav")
        val cancelAudio = File(context.filesDir, "v1-voice-cancel.wav")
        assertTrue(requestAudio.exists() && cancelAudio.exists())
        var scenario: ActivityScenario<V1ConversationActivity>? = null
        var web: WebView? = null
        val report = JSONObject().put("test", "Spoken cancellation during actual assistant work")
        var beforeIds = emptySet<String>()
        try {
            if (originalWait == 5L) setWait(12)
            val before = coordinator.api.request("/state")
            val revision = before.getLong("revision")
            val startWait = repository.snapshot.value.configuration.settings.waitSeconds
            beforeIds = before.getJSONArray("proposals").objects().map { it.getString("id") }.toSet()
            val beforeMessageCount = before.getJSONArray("messages").length()
            scenario = ActivityScenario.launch(V1ConversationActivity::class.java)
            await("conversation loaded", 35_000) {
                scenario!!.onActivity { web = findWebView(it.window.decorView) }
                web != null && evaluate(web!!, "document.getElementById('connection')?.textContent || ''").contains("Connected")
            }
            val view = web!!
            val request64 = Base64.encodeToString(requestAudio.readBytes(), Base64.NO_WRAP)
            val cancel64 = Base64.encodeToString(cancelAudio.readBytes(), Base64.NO_WRAP)
            evaluate(view, """
                (()=>{
                  const t=window.__cancelVoice={events:[],buffers:[]};
                  const Original=window.RTCPeerConnection;
                  window.RTCPeerConnection=class extends Original {
                    constructor(...args){super(...args);t.peer=this;}
                    createDataChannel(...args){const c=super.createDataChannel(...args);c.addEventListener('message',e=>{try{t.events.push(JSON.parse(e.data))}catch{}});return c;}
                  };
                  const ac=t.context=new AudioContext({sampleRate:24000}),dest=ac.createMediaStreamDestination();
                  const clock=t.clock=ac.createConstantSource();clock.offset.value=.00001;clock.connect(dest);clock.start();
                  navigator.mediaDevices.getUserMedia=async()=>dest.stream;
                  Promise.all(['$request64','$cancel64'].map(b=>ac.decodeAudioData(Uint8Array.from(atob(b),c=>c.charCodeAt(0)).buffer))).then(buffers=>{t.buffers=buffers;t.ready=true});
                  t.play=async index=>{await ac.resume();const source=ac.createBufferSource();source.buffer=t.buffers[index];source.connect(dest);source.start();};
                  return 'ready';
                })()
            """.trimIndent())
            await("two audio clips decoded", 8_000) { evaluate(view, "Boolean(window.__cancelVoice.ready)") == "true" }
            evaluate(view, "document.getElementById('voice').click(); 'connect'")
            await("voice connected", 50_000) { evaluate(view, "document.getElementById('voice').classList.contains('live')") == "true" }
            evaluate(view, "window.__cancelVoice.play(0); 'request'")
            await("Assistant is actually working", 35_000) { runBlocking { coordinator.api.request("/state").optBoolean("busy") } }
            report.put("workObservedBeforeCancellation", true)
            val cancelledAt = System.currentTimeMillis()
            evaluate(view, "window.__cancelVoice.play(1); 'spoken cancel'")
            await("Live heard spoken cancellation", 20_000) {
                evaluate(view, "window.__cancelVoice.events.filter(e=>e.type==='session.input_transcript.delta').map(e=>e.delta||'').join('').toLowerCase().includes('cancel')") == "true"
            }
            await("cancel completed without leaving a review or saving", 35_000) {
                val state = runBlocking { coordinator.api.request("/state") }
                !state.optBoolean("busy") && state.getJSONArray("proposals").objects().none { it.getString("id") !in beforeIds && it.optString("status") == "pending" }
            }
            val state = coordinator.api.request("/state")
            val newMessages = state.getJSONArray("messages").objects().drop(beforeMessageCount)
            assertTrue("Spoken cancel reached the backend", newMessages.any { it.optString("role") == "user" && it.optString("source") == "voice" && it.optString("text").contains("cancel", ignoreCase = true) })
            assertTrue("Backend confirmed cancellation", newMessages.any { it.optString("role") == "assistant" && it.optString("text").contains("cancel", ignoreCase = true) })
            report.put("backendMessages", JSONArray(newMessages)).put("backendCancellationVerified", true)
            assertEquals(revision, state.getLong("revision"))
            assertEquals(startWait, repository.snapshot.value.configuration.settings.waitSeconds)
            assertEquals("Interruption keeps the call alive", "true", evaluate(view, "document.getElementById('voice').classList.contains('live')"))
            report.put("cancelAudioToSettledMs", System.currentTimeMillis() - cancelledAt)
                .put("configurationUnchanged", true).put("callStayedOpen", true)
                .put("events", JSONArray(decodeJavascriptString(evaluate(view, "JSON.stringify(window.__cancelVoice.events)"))))
        } catch (error: Throwable) {
            report.put("failure", error.toString())
            throw error
        } finally {
            web?.let { view -> runCatching {
                report.put("finalClient", JSONObject(decodeJavascriptString(evaluate(view,
                    "JSON.stringify({events:window.__cancelVoice?.events,peer:window.__cancelVoice?.peer?.connectionState,status:document.getElementById('voiceStatus')?.textContent,alert:document.getElementById('alert')?.textContent})"))))
                evaluate(view, "Promise.resolve(window.FrictionStopVoice?.()).finally(()=>window.__cancelClosed=true); 'stop'")
                await("voice stopped", 8_000) { evaluate(view, "Boolean(window.__cancelClosed)") == "true" }
                evaluate(view, "window.__cancelVoice?.clock?.stop();window.__cancelVoice?.context?.close(); 'release audio'")
            } }
            scenario?.close()
            runCatching {
                val state = coordinator.api.request("/state")
                state.getJSONArray("proposals").objects().filter { it.getString("id") !in beforeIds && it.optString("status") == "pending" }.forEach {
                    coordinator.api.request("/cancel", JSONObject().put("proposalId", it.getString("id")))
                }
                if (state.optBoolean("busy")) coordinator.api.request("/cancel", JSONObject())
            }
            runCatching { setWait(originalWait) }.onFailure { report.put("restoreError", it.message) }
            File(context.filesDir, "v1-voice-cancellation-result.json").writeText(report.toString(2))
        }
    }

    private suspend fun setWait(value: Long) {
        val current = coordinator.api.request("/config")
        if (current.getJSONObject("config").getJSONObject("settings").getLong("waitSeconds") == value) { coordinator.sync(); return }
        val config = JSONObject(current.getJSONObject("config").toString())
        config.getJSONObject("settings").put("waitSeconds", value)
        val result = coordinator.api.request("/proposals", JSONObject().put("config", config).put("expectedRevision", current.getLong("revision")).put("title", "Restore physical voice test setting"))
        val proposal = result.optJSONObject("proposal") ?: result
        coordinator.api.request("/proposals/${proposal.getString("id")}/confirm", JSONObject().put("version", proposal.getLong("version")).put("operationId", UUID.randomUUID().toString()))
        assertTrue(coordinator.sync())
    }

    private fun findWebView(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) } else null
    private fun evaluate(view: WebView, script: String): String {
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result.set(it); latch.countDown() } }
        assertTrue("JavaScript response", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }
    private fun await(label: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(500)
        }
        fail("Timed out waiting for $label")
    }
    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
    private fun decodeJavascriptString(value: String): String = JSONArray("[$value]").getString(0)
}
