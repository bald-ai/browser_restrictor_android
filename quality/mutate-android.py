"""Sequential, bounded mutation checks. Requires exclusive checkout access.
Example: python3 quality/mutate-android.py api
Only JUnit assertion failures count as detected; compiler/setup failures do not.
"""

import os as _session_os, sys as _session_sys, subprocess as _session_subprocess
from pathlib import Path as _SessionPath
if not _session_os.environ.get('VERIFICATION_SESSION_ROOT'):
    raise SystemExit(_session_subprocess.call(['python3', str(_SessionPath(__file__).resolve().parents[1] / 'scripts/verification-session.py'), 'mutations:android', *_session_sys.argv[1:]]))
import signal as _session_signal
_session_signal.signal(_session_signal.SIGTERM, lambda *_: _session_sys.exit(143))
import hashlib,json,pathlib,subprocess,sys,time,os,xml.etree.ElementTree as ET
from source_manifest import source_manifest
from mutation_results import is_assertion_failure
root=pathlib.Path(__file__).resolve().parent.parent
groups={
 'video':('v1/V1VideoActivity.kt','*V1VideoActivityTest',[
  ('upload-empty-failed-capture','event.hasError() && file.length() == 0L','false'),
  ('leave-capture-running','status = "Saving recording…"; recording?.stop()','status = "Saving recording…"; Unit'),
  ('misreport-capture-failure','catch (error: Exception) { phase = "error"; status = error.message','catch (error: Exception) { phase = "ready"; status = error.message')]),
 'waitguards':('domain/RestrictionEngine.kt','*GatePreconditionsTest',[
  ('allow-unfocused-progress','GateMode.WAITING ||\n            !runtime.focusedGateMatches(expectedGateSessionId)\n        ) {','GateMode.WAITING\n        ) {'),
  ('allow-unavailable-progress','if (underlyingMode(state, runtime, nowWallMs) != GateMode.WAITING ||\n            !runtime.focusedGateMatches(expectedGateSessionId)\n        ) {','if (!runtime.focusedGateMatches(expectedGateSessionId)\n        ) {'),
  ('ignore-completion-token','gateSessionId != null && gateSessionId == expectedGateSessionId && gateFocused','gateFocused'),
  ('complete-unfocused-wait','!runtime.focusedGateMatches(expectedGateSessionId) ||','false ||'),
  ('complete-unavailable-wait','!runtime.focusedGateMatches(expectedGateSessionId) ||\n            underlyingMode(state, runtime, nowWallMs) != GateMode.WAITING','!runtime.focusedGateMatches(expectedGateSessionId)'),
  ('complete-other-wait','pending.id != expectedPendingWaitId ||','false ||'),
  ('complete-partial-wait','pending.accumulatedFocusMs < pending.requiredFocusMs ||','false ||')]),
 'emergencyskip':('domain/RestrictionEngine.kt','*RestrictionEngineTest',[
  ('ignore-daily-skip-limit','val skipEligible = remainingSkips > 0 &&','val skipEligible = true &&'),
  ('allow-any-gate-mode','            skipModeEligible &&\n','            true &&\n'),
  ('allow-second-emergency-session','            !activeUnlockIsCurrent &&\n','            true &&\n')]),
 'coordinator':('v1/V1Coordinator.kt','*V1CoordinatorTest',[
  ('credit-preview-evidence','val preview = evidence.optBoolean("preview") || json.optBoolean("preview")','val preview = json.optBoolean("preview")'),
  ('delete-before-durable-reward','check(repository.applyTaskReceipt(receipt))','check(true)'),
  ('submit-active-capture','if (V1GpsService.activeAttemptId == evidence.getString("attemptId")) return@forEach','if (false) return@forEach'),
  ('omit-phone-ack','if (envelope.optLong("appliedRevision", -1) < config.revision)','if (false)')]),
 'gpsservice':('v1/V1GpsService.kt','*V1GpsServiceTest',[
  ('accept-stale-fix','!in 0..10_000','!in 0..100_000'),
  ('accept-imprecise-start','location.accuracy > 25 || mock || !location.hasAccuracy()','location.accuracy > 250 || mock || !location.hasAccuracy()'),
  ('accept-mock-start','location.accuracy > 25 || mock || !location.hasAccuracy()','location.accuracy > 25 || !location.hasAccuracy()'),
  ('lose-interrupted-evidence','if (!ending && attempt != null)','if (false)')]),
 'sprintjudge':('domain/SprintEvaluator.kt','*Sprint*Test',[
  ('extend-speed-dip','<= MAX_SPEED_DIP_DURATION_MS','<= MAX_SPEED_DIP_DURATION_MS * 2'),
  ('count-position-jitter','if (isStationaryJitter(prefix, totals)) continue','if (false) continue'),
  ('allow-single-spike','if (endIdx < 1) continue','if (endIdx < 0) continue')]),
 'recording':('v1/GpsRecordingProgress.kt','*GpsRecordingProgressTest',[
  ('allow-equal-time','point.time <= previous','point.time < previous'),
  ('ignore-bad-accuracy-time','lastTime = point.time','if (point.accuracy <= 25) lastTime = point.time'),
  ('clear-invalid-recording','if (failure != null) return','failure = null'),
  ('allow-mock-evidence','if (mock)','if (false)'),
  ('display-invalid-distance','if (failure == null) distance.meters else 0.0','distance.meters')]),
 'receipts':('domain/TaskRewardPolicy.kt','*ReceiptValidationTest',[
  ('accept-unverified','configRevision < 0 || !verified','configRevision < 0'),
  ('ignore-precise-value','BigDecimal(measuredUnitsText).toLong() != value','false'),
  ('allow-manual-video','manualCompletion && (!fallbackEligible || task.verifier != "gps")','false')]),
 'rewardledger':('domain/TaskRewardPolicy.kt','*ConfigurableTasksTest',[
  ('replay-recording','.find { it.measurement.id == receipt.id || it.measurement.attemptId == receipt.attemptId }','.find { false }'),
  ('restore-expired-reward','val eligible = rewardEligible && finishedAtWallMs >= state.lastDailyRewardResetWallMs &&\n        dayKey == PeriodCalculator.keysAt(nowWallMs, pinnedZone, pinnedSettings).daily','val eligible = true')]),
 'serializer':('data/AppStateSerializer.kt','*SavedStateValidationTest',[
  ('skip-exercise-validation','        validateExerciseReceipt(state)','        Unit'),
  ('allow-unlock-overlap','if (state.activeUnlock != null && state.pendingWait != null)','if (false)'),
  ('skip-daily-bounds','        validateDailyCounter(state)','        Unit'),
  ('skip-protection-checks','        validateProtectionSettings(state)','        Unit'),
  ('skip-task-accounting','        validateTaskAccounting(state)','        Unit'),
  ('skip-wait-validation','        validateUnlockState(state)','        Unit'),
  ('skip-reminder-validation','        validateReminder(state)','        Unit'),
  ('skip-correction-validation','        validateCorrectionHistory(state)','        Unit'),
  ('allow-out-of-order-cycles','previousCycleStart?.let { it >= cycle.cycleStartDate } == true','false')]),
 'gps':('v1/GpsDistanceTracker.kt','*GpsDistance*',[
  ('poor-accuracy','p.accuracy > 25','p.accuracy > 250'),
  ('gap','seconds > 15','seconds > 150'),
  ('noise-anchor','if (metres < max(2.0, (a.accuracy + p.accuracy) * .35)) return','if (metres < max(2.0, (a.accuracy + p.accuracy) * .35)) { anchor = p; return }'),
  ('device-vehicle','(measured ?: inferred) > band.maximum','false'),
  ('position-vehicle','inferred > band.maximum','false'),
  ('sprint-position','inferred >= band.minimumPosition','true'),
  ('device-minimum','(measured ?: band.minimumDevice) >= band.minimumDevice','true'),
  ('double-distance','meters += metres','meters += metres * 2')]),
 'accounting':('data/RestrictorRepository.kt','*RestrictorRepositoryPolicyTest',[
  ('skip-period-flush','if (clockChanged || periodChanged) flushUsageBufferLocked(forceSummaryFlush = true)','if (clockChanged) flushUsageBufferLocked(forceSummaryFlush = true)'),
  ('ignore-unlock-expiry-cap','?.coerceAtMost(intervalEnd)','?.let { intervalEnd }'),
  ('ignore-budget-boundary','return !isEmergency && tightRemaining != null && bufferedDuration >= tightRemaining','return false'),
  ('skip-durable-normalization','if (persistLocked(normalized)) {','if (true) {')]),
 'api':('v1/V1Api.kt','*V1ApiContractTest',[
  ('omit-authentication','if (authenticate) setRequestProperty','if (false) setRequestProperty'),
  ('accept-error-response','check(code in 200..299)','check(true)'),
  ('wrong-upload-end-time','field("endedAt", endedAt.toString())','field("endedAt", "0")'),
  ('discard-saved-token','.putString("token", identity.getString("token")).commit()','.putString("token", "").commit()')])}
group=sys.argv[1];relative,tests,mutations=groups[group]
source=root/'app/src/main/java/com/michalkrsik/internetrestrictor'/relative
original=source.read_bytes();code=original.decode();results=[];inputs=source_manifest()
run_id=group+'-'+str(time.time_ns());logs=root/'quality/logs'/run_id;logs.mkdir()
for name,before,after in mutations:
 if len(sys.argv)>2 and name not in sys.argv[2:]:continue
 assert code.count(before)==1,(name,code.count(before))
 changed=code.replace(before,after).encode();source.write_bytes(changed);log=logs/(name+'.log');started=time.time()
 try:
  with log.open('w')as output:
   java_home=['-Dorg.gradle.java.home='+os.environ['QUALITY_JAVA_HOME']] if os.environ.get('QUALITY_JAVA_HOME') else []
   run=subprocess.run(['./gradlew',*java_home,':app:testDebugUnitTest','--tests',tests],cwd=root,stdout=output,stderr=subprocess.STDOUT,timeout=180)
  cases=[]
  for file in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
   if file.stat().st_mtime>=started:cases.extend(ET.parse(file).getroot().findall('testcase'))
  failures=[c for c in cases if c.find('failure')is not None]
  assertions=[c for c in failures if is_assertion_failure(c.find('failure'))]
  status='detected' if run.returncode and assertions else 'errored' if run.returncode or not cases else 'surviving'
  results.append(dict(name=name,status=status,exit=run.returncode,tests=len(cases),failures=len(failures),assertionFailures=len(assertions),log=str(log.relative_to(root))))
 except subprocess.TimeoutExpired:results.append(dict(name=name,status='timed-out',exit=None,log=str(log.relative_to(root))))
 finally:
  if source.read_bytes()!=changed:
   (logs/'original-source.backup').write_bytes(original)
   raise RuntimeError('Source changed during mutation; preserving the concurrent edit and backup.')
  source.write_bytes(original)
 report=dict(scope=relative+' with '+tests,sourceSha256=hashlib.sha256(original).hexdigest(),sourceFile=str(source.relative_to(root)),inputsSha256=inputs,restored=source.read_bytes()==original,mutations=results)
 (root/'quality/metrics'/f'{run_id}.json').write_text(json.dumps(report,indent=2)+'\n')
 print(results[-1],flush=True)

print("Restoration verified:", source.relative_to(root), hashlib.sha256(source.read_bytes()).hexdigest(), source.read_bytes()==original, flush=True)
