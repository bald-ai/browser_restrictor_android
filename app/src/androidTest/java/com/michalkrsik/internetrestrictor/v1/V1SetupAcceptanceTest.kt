package com.michalkrsik.internetrestrictor.v1

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.data.ConfigurationJson
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

/** Opt-in real phone -> hosted UI -> hosted API -> durable native setup. No API mocks. */
class V1SetupAcceptanceTest {
 private val inst=InstrumentationRegistry.getInstrumentation()
 private val context=inst.targetContext
 private val v1 get()=context.appContainer.v1
 private val repo get()=context.appContainer.repository
 private var scenario:ActivityScenario<V1ConversationActivity>?=null
 private lateinit var web:WebView
 private val checks=JSONArray()
 private val report=JSONObject().put("checks",checks)
 private val output get()=File(context.filesDir,"setup-acceptance.json")
 private fun save(){output.writeText(report.toString(2))}
 private fun pass(name:String,detail:Any=true){checks.put(JSONObject().put("name",name).put("detail",detail));save()}
 private fun eval(script:String):String {val value=AtomicReference<String>();val done=CountDownLatch(1);inst.runOnMainSync{web.evaluateJavascript(script){value.set(it);done.countDown()}};assertTrue(done.await(10,TimeUnit.SECONDS));return value.get()}
 private fun js(script:String)=eval("(()=>{$script;return true})()")
 private fun str(script:String)=JSONArray("[${eval(script)}]").getString(0)
 private fun waitFor(name:String,ms:Long=20000,p:()->Boolean){val end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){if(p())return;Thread.sleep(200)};fail("Timeout: $name; UI alert="+runCatching{str("document.getElementById('alert')?.textContent||''")}.getOrDefault("unavailable"))}
 private fun find(v:View):WebView?=if(v is WebView)v else if(v is ViewGroup)(0 until v.childCount).firstNotNullOfOrNull{find(v.getChildAt(it))}else null
 private fun open(){shell("input keyevent KEYCODE_HOME");Thread.sleep(700);scenario=ActivityScenario.launch(V1ConversationActivity::class.java);waitFor("WebView ready",35000){var found:WebView?=null;scenario!!.onActivity{found=find(it.window.decorView)};if(found!=null)web=found!!;found!=null&&eval("document.getElementById('connection')?.textContent==='Connected'")=="true"}}
 private fun click(selector:String){waitFor("click target $selector"){eval("Boolean(document.querySelector(${JSONObject.quote(selector)}))")=="true"};js("const b=document.querySelector(${JSONObject.quote(selector)});b.scrollIntoView({block:'center'});b.click()")}
 private fun form(values:JSONObject){js("const v=$values;for(const[k,x]of Object.entries(v)){const e=document.getElementById('editForm').elements[k];e.value=String(x);e.dispatchEvent(new Event('change',{bubbles:true}));e.dispatchEvent(new Event('input',{bubbles:true}))}")}
 private fun state()=runBlocking{v1.api.request("/state")}
 private fun pending():JSONObject?=state().getJSONArray("proposals").let{a->(0 until a.length()).map{a.getJSONObject(it)}.lastOrNull{it.getString("status")=="pending"}}
 private fun submit():JSONObject{js("document.getElementById('editForm').requestSubmit()");waitFor("editor closes"){eval("document.getElementById('editor').open")=="false"};waitFor("pending review"){pending()!=null};return pending()!!}
 private fun apply(p:JSONObject){val rev=p.getLong("baseRevision")+1;click("#applyChanges");waitFor("native application of revision $rev",30000){val s=state();s.getLong("revision")==rev&&s.getLong("appliedRevision")==rev&&repo.snapshot.value.configuration.revision==rev};val s=state();assertEquals(ConfigurationJson.parseConfiguration(s),repo.snapshot.value.configuration);val disk=runBlocking{File(context.filesDir,"datastore/restrictor_state.bin").inputStream().use{com.michalkrsik.internetrestrictor.data.AppStateSerializer.readFrom(it)}};assertEquals("Durable state matches applied revision",repo.snapshot.value.configuration,disk.configuration);pass("UI Apply, server readback and native acknowledgement",rev)}
 private fun screenshot(name:String){val bitmap=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();File(context.filesDir,"setup-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
 private fun settings(values:JSONObject):JSONObject{click("#setupTab");click("#editSettings");form(values);return submit()}
 private fun task(values:JSONObject,id:String?=null):JSONObject{click("#setupTab");click(if(id==null)"#addTask" else "[data-edit-task='$id']");form(values);return submit()}
 private fun nativeTask(id:String)=repo.snapshot.value.configuration.tasks.find{it.id==id}
 private suspend fun restore(original:JSONObject){val s=state();s.getJSONArray("proposals").let{a->for(i in 0 until a.length()){val p=a.getJSONObject(i);if(p.getString("status")=="pending")v1.api.request("/cancel",JSONObject().put("proposalId",p.getString("id")))}};val current=v1.api.request("/config");if(current.getJSONObject("config").toString()!=original.toString()){val p=v1.api.request("/proposals",JSONObject().put("config",original).put("expectedRevision",current.getLong("revision")).put("title","Restore setup after acceptance test"));v1.api.request("/proposals/${p.getString("id")}/confirm",JSONObject().put("version",p.getLong("version")).put("operationId",UUID.randomUUID().toString()))};assertTrue(v1.sync());assertEquals(ConfigurationJson.parseConfiguration(v1.api.request("/config")),repo.snapshot.value.configuration)}

 @Test fun allSettingsAndTaskLifecycle()=runBlocking<Unit>{
  assumeTrue(InstrumentationRegistry.getArguments().getString("v1SetupAcceptance")=="true")
  assertEquals("com.michalkrsik.browserrestrictor.v1dev",context.packageName);assertTrue(v1.sync());assertNull("Do not overwrite a user's pending review",pending())
  val original=v1.api.request("/config").getJSONObject("config");File(context.filesDir,"setup-original.json").writeText(original.toString(2));report.put("startedAt",System.currentTimeMillis()).put("original",original)
  try{
   open()
   val values=JSONObject().put("waitSeconds",2).put("minutesPerCredit",7).put("sessionMinutes","1, 2, 4").put("dailyBudgetMinutes",15).put("dayReset","03:15").put("youtubeCredits",2).put("emergencyMinutes",4).put("creditName","stars").put("unlockLabel","Open with intention").put("youtubeUnlockLabel","Earn YouTube").put("emergencyUnlockLabel","Use my escape")
   val p=settings(values);assertEquals(11,p.getJSONArray("cards").length());assertEquals(original.toString(),v1.api.request("/config").getJSONObject("config").toString());pass("All eleven edited settings remain unapplied during review")
   waitFor("new review automatically visible"){eval("(()=>{const r=document.getElementById('reviewTitle').getBoundingClientRect();return r.top>=0&&r.top<innerHeight/2})()")=="true"};pass("New review scrolls into view after editing instead of leaving old conversation visible");screenshot("eleven-cards")
   val cards=p.getJSONArray("cards")
   for(i in 0 until cards.length()){
    val card=cards.getJSONObject(i);click("[data-preview='${card.getString("id")}']");assertEquals("true",eval("document.getElementById('previewDialog').open"));assertTrue(str("document.getElementById('previewContent').textContent").isNotBlank());click("#returnToReview")
    if(i<cards.length()-1){click("#nextCard");Thread.sleep(400)}
   }
   pass("All eleven preview dialogs open and return; carousel navigation runs")
   apply(p)
   val expected=ConfigurationJson.parseConfiguration(state());assertEquals(expected,repo.snapshot.value.configuration);pass("All eleven values match native configuration",ConfigurationJson.settingsJson(expected.settings))
   scenario!!.recreate();waitFor("recreated WebView",25000){var replacement:WebView?=null;scenario!!.onActivity{replacement=find(it.window.decorView)};if(replacement!=null)web=replacement!!;replacement!=null&&eval("document.getElementById('connection')?.textContent==='Connected'")=="true"};assertEquals(expected,repo.snapshot.value.configuration);pass("Activity recreation retains the full saved setup")

   click("#setupTab");click("#editSettings");form(JSONObject().put("waitSeconds",-1));js("document.getElementById('editForm').requestSubmit()");waitFor("invalid setting rejected"){eval("!document.getElementById('editorError').hidden && document.getElementById('editorError').textContent.length>0")=="true"};assertEquals("true",eval("document.getElementById('editor').open"));assertEquals(expected,repo.snapshot.value.configuration);click("#closeEditor");pass("Invalid waiting time rejected without changing phone configuration")
   val grouped=settings(JSONObject().put("waitSeconds",3).put("minutesPerCredit",8));val gc=grouped.getJSONArray("cards");val waitCard=(0 until gc.length()).map{gc.getJSONObject(it)}.first{it.getString("key")=="waitSeconds"};val rewardCard=(0 until gc.length()).map{gc.getJSONObject(it)}.first{it.getString("key")=="minutesPerCredit"}
   click("[data-adjust='${waitCard.getString("id")}']");form(JSONObject().put("waitSeconds",4));val adjusted=submit();assertEquals(2,adjusted.getJSONArray("cards").length());pass("Adjust one card preserves the other pending change")
   val remove=(0 until adjusted.getJSONArray("cards").length()).map{adjusted.getJSONArray("cards").getJSONObject(it)}.first{it.getString("key")=="minutesPerCredit"};click("[data-remove='${remove.getString("id")}']");waitFor("one remaining review card"){pending()?.getJSONArray("cards")?.length()==1};click("#discardChanges");waitFor("review cancelled"){pending()==null};assertEquals(expected,repo.snapshot.value.configuration);pass("Remove one card and cancel preserve saved setup")
   val both=settings(JSONObject().put("waitSeconds",3).put("minutesPerCredit",8));apply(both);val bc=both.getJSONArray("cards");val undoCard=(0 until bc.length()).map{bc.getJSONObject(it)}.first{it.getString("key")=="waitSeconds"};click("[data-undo='${undoCard.getString("id")}']");waitFor("per-card undo"){repo.snapshot.value.configuration.settings.waitSeconds==2L&&repo.snapshot.value.configuration.settings.minutesPerCredit==8L};pass("Undo one saved card preserves the other saved change");click("#undoChanges");waitFor("whole group undo"){repo.snapshot.value.configuration.settings==expected.settings};pass("Whole request undo restores remaining values")

   val newTask=JSONObject().put("name","Acceptance brush").put("verifier","video").put("metric","seconds").put("unitsPerCredit",15).put("correctionMinimum",2).put("instructions","Count visible active toothbrushing seconds. Exclude talking and idle time.")
   val newProposal=task(newTask);val taskId=newProposal.getJSONArray("cards").getJSONObject(0).getString("key");apply(newProposal);assertEquals("seconds",nativeTask(taskId)!!.metric);assertEquals(2L,nativeTask(taskId)!!.correctionMinimum);pass("Add video seconds task through form, review and native sync",taskId)
   val edit=task(JSONObject().put("name","Acceptance reps").put("metric","repetitions").put("unitsPerCredit",3).put("correctionMinimum",1).put("instructions","Count complete visible squats, down and back up."),taskId);apply(edit);assertEquals("repetitions",nativeTask(taskId)!!.metric);assertEquals(3L,nativeTask(taskId)!!.unitsPerCredit);assertTrue(nativeTask(taskId)!!.instructions.contains("squats"));pass("Edit task name, instructions, reward threshold, minimum and video metric")
   apply(task(JSONObject().put("paused","true"),taskId));assertTrue(nativeTask(taskId)!!.paused);pass("Pause task applies natively")
   apply(task(JSONObject().put("paused","false"),taskId));assertFalse(nativeTask(taskId)!!.paused);pass("Unpause task applies natively")
   apply(task(JSONObject().put("verifier","gps").put("metric","meters").put("gpsMode","walk").put("unitsPerCredit",250).put("instructions","Walk outdoors; distance is checked from GPS."),taskId));assertEquals("walk",nativeTask(taskId)!!.gpsMode);assertEquals("meters",nativeTask(taskId)!!.metric);pass("Change existing task from video to GPS walk")
   apply(task(JSONObject().put("gpsMode","sprint"),taskId));assertEquals("sprint",nativeTask(taskId)!!.gpsMode);pass("GPS sprint mode applies natively")
   click("#setupTab");click("[data-edit-task='$taskId']");click("#removeTask");waitFor("removal review"){pending()!=null};assertNotNull(nativeTask(taskId));apply(pending()!!);assertNull(nativeTask(taskId));pass("Removal waits for approval then removes native task")
   report.put("passed",true)
  }catch(e:Throwable){report.put("failure",e.stackTraceToString());runCatching{screenshot("failure")};throw e}
  finally{scenario?.close();runCatching{restore(original);report.put("originalRestored",true)}.onFailure{report.put("restoreFailure",it.stackTraceToString())};report.put("finishedAt",System.currentTimeMillis());save()}
  assertTrue(report.optBoolean("originalRestored"))
 }
 private val automation get()=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
 private fun shell(command:String):String=automation.executeShellCommand(command).use{p->java.io.FileInputStream(p.fileDescriptor).bufferedReader().readText()}
 private fun nativeNodes():List<android.view.accessibility.AccessibilityNodeInfo>{val out=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>();fun walk(n:android.view.accessibility.AccessibilityNodeInfo?){if(n==null)return;out.add(n);for(i in 0 until n.childCount)walk(n.getChild(i))};walk(automation.rootInActiveWindow);return out}
 private fun nativeText()=nativeNodes().mapNotNull{it.text?.toString()?:it.contentDescription?.toString()}.joinToString("\n")
 private fun nativeClick(text:String){waitFor("native button $text",12000){val n=nativeNodes().firstOrNull{it.text?.toString()==text||it.contentDescription?.toString()==text};if(n==null){shell("input swipe 540 1800 540 700 350");false}else{var target:android.view.accessibility.AccessibilityNodeInfo?=n;while(target!=null&&!target.isClickable)target=target.parent;target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true}}}
 private fun mainScreen(){shell("input keyevent KEYCODE_HOME");Thread.sleep(700);shell("am start -n ${context.packageName}/com.michalkrsik.internetrestrictor.MainActivity");Thread.sleep(1200)}
 private fun chrome(){shell("am start -a android.intent.action.VIEW -d https://example.com -p com.android.chrome")}
 private fun youtube(){val intent=context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")!!;intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(intent)}
 private fun nativeSnapshot():JSONObject{val s=repo.snapshot.value;return JSONObject().put("at",System.currentTimeMillis()).put("mode",s.canonicalMode.name).put("credits",s.earnedBudgetRoundsToday).put("minutes",s.budgetSnapshots.firstOrNull()?.limitMinutes).put("remainingMs",s.budgetSnapshots.firstOrNull()?.remainingMs).put("partialProgress",s.taskCreditProgress).put("configuration",ConfigurationJson.configurationJson(s.configuration)).put("activeUnlock",s.activeUnlock?.toString())}
 private suspend fun verifiedCredit(taskId:String):JSONObject{
  val clip=File(context.filesDir,"setup-pushups.mp4");assertTrue("Upload source fixture first",clip.exists());val copy=File(context.filesDir,"setup-upload-${UUID.randomUUID()}.mp4");clip.copyTo(copy)
  val now=System.currentTimeMillis();val attempt=JSONObject().put("attemptId",UUID.randomUUID().toString()).put("taskId",taskId).put("startedAt",now-6800).put("configRevision",repo.snapshot.value.configuration.revision)
  v1.api.request("/attempts",attempt);val result=v1.saveVideo(attempt,copy,now);assertEquals(2,result.getInt("measuredUnits"));assertTrue(repo.snapshot.value.taskReceipts.any{it.measurement.attemptId==result.getString("attemptId")});pass("Real push-up video submitted from phone and consumed by native reward ledger",result);return result
 }
 @Test fun realRuntimeRewardsAndResets()=runBlocking<Unit>{
  assumeTrue(InstrumentationRegistry.getArguments().getString("v1SetupRuntime")=="true");assertEquals("com.michalkrsik.browserrestrictor.v1dev",context.packageName);assertTrue(v1.sync());assertNull(pending())
  val original=v1.api.request("/config").getJSONObject("config");File(context.filesDir,"setup-runtime-original.json").writeText(original.toString(2))
  val originalServices=shell("settings get secure enabled_accessibility_services").trim();val originalAuto=originalServices.split(':').filterNot{it.contains("com.michalkrsik.browserrestrictor/")||it.contains("com.michalkrsik.browserrestrictor.v1dev/")}.joinToString(":")
  report.put("originalServices",originalServices);assertTrue("Existing accessibility services must be captured",originalServices.contains("com.wispr.flowapp"));val receipts=mutableListOf<String>();report.put("startedAt",System.currentTimeMillis()).put("before",nativeSnapshot());assertEquals("No real dev rewards may be present before reset test",0L,repo.snapshot.value.earnedBudgetRoundsToday)
  try{
   shell("settings put secure enabled_accessibility_services $originalAuto");shell("settings put secure enabled_accessibility_services ${originalAuto}:com.michalkrsik.browserrestrictor.v1dev/com.michalkrsik.internetrestrictor.monitoring.ChromeAccessibilityService")
   shell("settings put secure accessibility_enabled 1");report.put("testServices",shell("settings get secure enabled_accessibility_services"));
   open();apply(settings(JSONObject().put("waitSeconds",2).put("minutesPerCredit",4).put("sessionMinutes","1, 2, 3").put("youtubeCredits",2).put("emergencyMinutes",3).put("creditName","sparks").put("unlockLabel","Open with intention").put("youtubeUnlockLabel","Earn YouTube").put("emergencyUnlockLabel","Use my escape")))
   val p=task(JSONObject().put("name","Setup runtime pushups").put("verifier","video").put("metric","repetitions").put("unitsPerCredit",2).put("correctionMinimum",1).put("instructions","Count each complete push-up lowering and rising once."));val taskId=p.getJSONArray("cards").getJSONObject(0).getString("key");apply(p)
   val first=verifiedCredit(taskId);receipts.add(first.getString("attemptId"));assertEquals(1L,repo.snapshot.value.earnedBudgetRoundsToday);assertEquals(4L,repo.snapshot.value.budgetSnapshots.first().limitMinutes)
   apply(settings(JSONObject().put("minutesPerCredit",7)));assertEquals(4L,repo.snapshot.value.budgetSnapshots.first().limitMinutes);pass("Changing reward rate preserves four already-earned minutes")
   val second=verifiedCredit(taskId);receipts.add(second.getString("attemptId"));assertEquals(2L,repo.snapshot.value.earnedBudgetRoundsToday);assertEquals(11L,repo.snapshot.value.budgetSnapshots.first().limitMinutes);pass("Next verified credit earns seven minutes: four plus seven equals eleven",nativeSnapshot())
   scenario!!.close();scenario=null;mainScreen();waitFor("native dashboard updated"){nativeText().contains("minutes per spark")};screenshot("runtime-dashboard");nativeClick("Setup runtime pushups");waitFor("custom video recorder uses task rules"){nativeText().contains("2 repetitions = 1 sparks")};screenshot("runtime-video-route");shell("input keyevent KEYCODE_BACK");pass("Native dashboard label and custom video recorder routing")
   pass("Before Chrome launch",nativeSnapshot().put("services",shell("settings get secure enabled_accessibility_services")));chrome();waitFor("actual Chrome gate",20000){repo.snapshot.value.canonicalMode==com.michalkrsik.internetrestrictor.domain.GateMode.PAUSED};val gate=nativeText();assertTrue(gate.contains("1 minute session"));assertTrue(gate.contains("2 minute session"));assertTrue(gate.contains("3 minute session"));assertTrue(gate.contains("Open with intention"));assertTrue(gate.contains("Use my escape"));screenshot("runtime-chrome-gate");pass("Actual Chrome gate renders all three configured session lengths and custom buttons",gate)
   nativeClick("1 minute session");val start=System.currentTimeMillis();nativeClick("Open with intention · 1 minutes");waitFor("normal unlock after configured pause"){repo.snapshot.value.canonicalMode==com.michalkrsik.internetrestrictor.domain.GateMode.ACCESS_GRANTED_NORMAL};val elapsed=System.currentTimeMillis()-start;assertTrue("Two-second wait elapsed: $elapsed",elapsed in 1700..7000);assertEquals(60000L,repo.snapshot.value.activeUnlock!!.let{it.expiresAtWallMs-it.grantedAtWallMs});pass("Real button starts one-minute session after two-second wait",JSONObject().put("elapsedMs",elapsed).put("snapshot",nativeSnapshot()))
   assertTrue(repo.cancelActiveUnlock());chrome();waitFor("gate after ending test session"){repo.snapshot.value.canonicalMode==com.michalkrsik.internetrestrictor.domain.GateMode.PAUSED};nativeClick("Use my escape");waitFor("emergency access"){repo.snapshot.value.canonicalMode==com.michalkrsik.internetrestrictor.domain.GateMode.ACCESS_GRANTED_EMERGENCY};assertEquals(180000L,repo.snapshot.value.activeUnlock!!.let{it.expiresAtWallMs-it.grantedAtWallMs});pass("Custom emergency button grants configured three minutes",nativeSnapshot());assertTrue(repo.cancelActiveUnlock())
   youtube();waitFor("YouTube at configured two-credit threshold"){repo.snapshot.value.canonicalMode==com.michalkrsik.internetrestrictor.domain.GateMode.YOUTUBE_ACCESS_GRANTED};pass("YouTube opens at configured two-credit threshold")
   open();apply(settings(JSONObject().put("youtubeCredits",3)));scenario!!.close();scenario=null;youtube();waitFor("YouTube locked below new threshold"){repo.snapshot.value.canonicalMode==com.michalkrsik.internetrestrictor.domain.GateMode.YOUTUBE_LOCKED};assertTrue(nativeText().contains("Earn YouTube"));assertTrue(nativeText().contains("2/3 sparks"));screenshot("runtime-youtube-locked");pass("Increasing threshold locks YouTube; custom button and credit label displayed")
   // Schedule actual boundaries through the UI. No device clock changes or fake clock ticks.
   open();val now=java.time.ZonedDateTime.now();val daily=now.plusMinutes(2).withSecond(0).withNano(0);val format=java.time.format.DateTimeFormatter.ofPattern("HH:mm")
   apply(settings(JSONObject().put("dailyBudgetMinutes",15).put("dayReset",daily.format(format))))
   // A half credit deliberately establishes partial progress; verifier still runs on the real video.
   apply(task(JSONObject().put("unitsPerCredit",4),taskId));val partial=verifiedCredit(taskId);receipts.add(partial.getString("attemptId"));assertEquals(.5,repo.snapshot.value.taskCreditProgress,.001);assertEquals(2L,repo.snapshot.value.earnedBudgetRoundsToday)
   pass("Before actual reset boundaries",JSONObject().put("dailyAt",daily.toInstant().toEpochMilli()).put("snapshot",nativeSnapshot()));scenario!!.close();scenario=null;mainScreen()
   waitFor("real daily reset",150000){System.currentTimeMillis()>=daily.toInstant().toEpochMilli()+1000&&repo.snapshot.value.earnedBudgetRoundsToday==0L};assertEquals(0.0,repo.snapshot.value.taskCreditProgress,0.0);assertEquals(15L,repo.snapshot.value.budgetSnapshots.first().limitMinutes);pass("Actual daily reset grants the allowance and clears credits and partial progress",nativeSnapshot());screenshot("after-daily-reset")
   report.put("passed",true)
  }catch(e:Throwable){report.put("failure",e.stackTraceToString());runCatching{screenshot("runtime-failure")};throw e}
  finally{scenario?.close();runCatching{repo.cancelActiveUnlock();for(id in receipts){val s=repo.snapshot.value;val saved=s.taskReceipts.find{it.measurement.attemptId==id};if(saved!=null&&saved.measurement.id in s.modifiableTaskReceiptIds)v1.correct(id,0.0)};restore(original);report.put("originalRestored",true)}.onFailure{report.put("restoreFailure",it.stackTraceToString())};shell("settings put secure enabled_accessibility_services $originalServices");report.put("after",nativeSnapshot()).put("finishedAt",System.currentTimeMillis());File(context.filesDir,"setup-runtime.json").writeText(report.toString(2));save()}
  assertTrue(report.optBoolean("originalRestored"))
 }

 @Test fun agentGroupedSetupAndNativeTaskRouting()=runBlocking<Unit>{
  assumeTrue(InstrumentationRegistry.getArguments().getString("v1SetupAgent")=="true");assertEquals("com.michalkrsik.browserrestrictor.v1dev",context.packageName);assertTrue(v1.sync());assertNull(pending());val original=v1.api.request("/config").getJSONObject("config")
  report.put("startedAt",System.currentTimeMillis())
  fun chat(message:String){js("document.getElementById('message').value=${JSONObject.quote(message)};document.getElementById('chatForm').requestSubmit()");waitFor("agent response",120000){!state().optBoolean("busy")&&eval("document.getElementById('send').disabled")=="false"};pass("Actual phone chat response",state().getJSONArray("messages"))}
  try{
   open();val revision=repo.snapshot.value.configuration.revision
   chat("I want to add a new activity task, but I haven't decided what activity or how to measure it. Ask me what you need before creating it.");assertNull(pending());assertEquals(revision,repo.snapshot.value.configuration.revision);pass("Agent asks for missing task details without inventing a review or changing settings")
   chat("Add a task called Brush teeth acceptance using video, measured in seconds, one credit per 15 active seconds, correction only after at least 1 observed second. Count visible brushing of the person's teeth with a toothbrush; exclude talking and idle time. Also set my waiting time to 6 seconds, minutes per credit to 5, and unlock button label to Take a moment. Prepare all four changes together for review.")
   val p=pending()!!;assertEquals(4,p.getJSONArray("cards").length());assertEquals(revision,repo.snapshot.value.configuration.revision);val tc=(0 until p.getJSONArray("cards").length()).map{p.getJSONArray("cards").getJSONObject(it)}.first{it.getString("kind")=="task"};val id=tc.getString("key");assertEquals(15,tc.getJSONObject("after").getInt("unitsPerCredit"));screenshot("agent-four-cards");apply(p);assertEquals(6L,repo.snapshot.value.configuration.settings.waitSeconds);assertEquals(5L,repo.snapshot.value.configuration.settings.minutesPerCredit);assertEquals("Take a moment",repo.snapshot.value.configuration.settings.unlockLabel);pass("Agent-created four-change review confirmed by UI and active on phone")
   // Make the referenced task visibly selected, allowing normal carousel context propagation.
   js("const c=document.querySelector('[data-card-id=\"${tc.getString("id")}\"]');const r=document.getElementById('cards');r.scrollLeft=c.offsetLeft-r.firstElementChild.offsetLeft;r.dispatchEvent(new Event('scroll'))")
   waitFor("selected task reaches backend"){state().optString("selectedCardId")==tc.getString("id")}
   chat("Change this task to 10 seconds per credit. Keep everything else as it is.")
   val change=pending()!!;assertEquals(1,change.getJSONArray("cards").length());assertEquals(id,change.getJSONArray("cards").getJSONObject(0).getString("key"));apply(change);assertEquals(10L,nativeTask(id)!!.unitsPerCredit);assertEquals(6L,repo.snapshot.value.configuration.settings.waitSeconds);pass("Agent resolves this task from visible card and leaves other settings unchanged")
   scenario!!.close();scenario=null;mainScreen();nativeClick("Brush teeth acceptance");waitFor("agent-created video task recorder"){nativeText().contains("10 seconds = 1 credits")};screenshot("agent-teeth-recorder");shell("input keyevent KEYCODE_BACK");pass("Agent-created seconds task launches native camera with approved units and definition")
   open();apply(task(JSONObject().put("paused","true"),id));scenario!!.close();scenario=null;mainScreen();shell("input swipe 540 1800 540 500 400");Thread.sleep(400);assertFalse(nativeText().contains("Brush teeth acceptance"));pass("Paused task disappears from native activity choices")
   open();apply(task(JSONObject().put("paused","false"),id));scenario!!.close();scenario=null;mainScreen();nativeClick("Brush teeth acceptance");waitFor("unpaused task is usable again"){nativeText().contains("10 seconds = 1 credits")};shell("input keyevent KEYCODE_BACK");pass("Unpaused task returns and opens recorder")
   open();val gps=task(JSONObject().put("name","Acceptance outdoor task").put("verifier","gps").put("metric","meters").put("gpsMode","walk").put("unitsPerCredit",200).put("correctionMinimum",1).put("instructions","Count qualifying GPS walking distance."));val gpsId=gps.getJSONArray("cards").getJSONObject(0).getString("key");apply(gps);scenario!!.close();scenario=null;mainScreen();nativeClick("Acceptance outdoor task");waitFor("new GPS walk opens correct recorder"){nativeText().contains("Walk anywhere.")&&nativeText().contains("200 meters = 1 credits")};screenshot("gps-walk-recorder");shell("input keyevent KEYCODE_BACK");pass("New GPS task launches walk recorder with configured distance")
   open();apply(task(JSONObject().put("gpsMode","sprint"),gpsId));scenario!!.close();scenario=null;mainScreen();nativeClick("Acceptance outdoor task");waitFor("GPS sprint opens updated recorder"){nativeText().contains("must reach 15 km/h")};screenshot("gps-sprint-recorder");shell("input keyevent KEYCODE_BACK");pass("Editing GPS mode changes actual recorder behavior instructions to sprint")
   open();click("#setupTab");click("[data-edit-task='$gpsId']");click("#removeTask");waitFor("removal pending"){pending()!=null};apply(pending()!!);scenario!!.close();scenario=null;mainScreen();shell("input swipe 540 1800 540 500 400");Thread.sleep(400);assertFalse(nativeText().contains("Acceptance outdoor task"));pass("Removed task disappears from actual native task choices")
   report.put("passed",true)
  }catch(e:Throwable){report.put("failure",e.stackTraceToString());runCatching{screenshot("agent-failure")};throw e}
  finally{scenario?.close();runCatching{restore(original);report.put("originalRestored",true)}.onFailure{report.put("restoreFailure",it.stackTraceToString())};report.put("finishedAt",System.currentTimeMillis());File(context.filesDir,"setup-agent.json").writeText(report.toString(2));save()}
  assertTrue(report.optBoolean("originalRestored"))
 }

 @Test fun stageProcessRestartProbe()=runBlocking<Unit>{
  assumeTrue(InstrumentationRegistry.getArguments().getString("v1SetupPersistence")=="true");assertTrue(v1.sync());assertNull(pending());val original=v1.api.request("/config").getJSONObject("config");File(context.filesDir,"setup-restart-original.json").writeText(original.toString())
  try{open();apply(settings(JSONObject().put("waitSeconds",9).put("minutesPerCredit",6).put("sessionMinutes","2, 4, 6").put("dailyBudgetMinutes",20).put("dayReset","05:15").put("youtubeCredits",3).put("emergencyMinutes",8).put("creditName","tokens").put("unlockLabel","Open calmly").put("youtubeUnlockLabel","Open video").put("emergencyUnlockLabel","Use a break")));File(context.filesDir,"setup-restart-expected.json").writeText(state().toString());pass("Eleven non-default settings saved for process-death probe");report.put("passed",true)}catch(e:Throwable){restore(original);throw e}finally{scenario?.close();File(context.filesDir,"setup-restart-stage.json").writeText(report.toString(2))}
 }
 @Test fun verifyProcessRestartProbe()=runBlocking<Unit>{
  assumeTrue(InstrumentationRegistry.getArguments().getString("v1SetupPersistence")=="true");val original=JSONObject(File(context.filesDir,"setup-restart-original.json").readText());val expected=ConfigurationJson.parseConfiguration(JSONObject(File(context.filesDir,"setup-restart-expected.json").readText()))
  try{val disk=File(context.filesDir,"datastore/restrictor_state.bin").inputStream().use{com.michalkrsik.internetrestrictor.data.AppStateSerializer.readFrom(it)};assertEquals(expected,disk.configuration);repo.awaitLoaded();assertEquals(expected,repo.snapshot.value.configuration);open();assertEquals(expected,repo.snapshot.value.configuration);click("#setupTab");assertTrue(str("document.getElementById('settingsList').textContent").contains("Open calmly"));screenshot("restart-persisted");pass("Actual process restart preserves all eleven settings in disk, native state and visible setup",ConfigurationJson.settingsJson(expected.settings));report.put("passed",true)}finally{scenario?.close();restore(original);report.put("originalRestored",true);File(context.filesDir,"setup-restart-verified.json").writeText(report.toString(2))}
 }

}
