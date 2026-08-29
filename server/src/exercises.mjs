export const ExerciseMetric = Object.freeze({
  REPETITIONS: "repetitions",
  HOLD_SECONDS: "hold_seconds",
  NONE: "none",
});

export const CLASSIFIER_EXERCISE_IDS = Object.freeze([
  "push_up",
  "plank",
  "outdoor_lat_pulldown",
  "unsupported",
]);

export const DENSIFY_FACTOR = 4;

const pushupPrompt = [
  "Count push-up repetitions in casual phone videos.",
  "Count each complete lowering-and-rising motion cycle once.",
  "The whole body does not need to be visible; continuous repeated motion of the visible head, shoulders, torso, or arms is sufficient.",
  "Tolerate imperfect framing, ordinary form, incomplete depth, brief occlusion, and camera shake.",
  "Ignore setup, repositioning, unrelated movement, cuts, loops, and motion before or after the exercise sequence.",
  "Identify the exercise segment, track its successive high and low body positions chronologically, and count each return from a low position to a high position as one cycle.",
  "Before answering, scan the full exercise segment a second time and reconcile the cycle count; do not estimate from elapsed time.",
  "Return zero only when no push-up-like repetition cycles are visible.",
].join(" ");

const plankPrompt = [
  "Measure a plank hold in casual phone videos.",
  "A valid plank is a sustained body hold roughly parallel to the ground, typically on hands or forearms.",
  "The whole body does not need to be visible; a continuous held torso, hip, or shoulder line is sufficient.",
  "Tolerate imperfect framing, ordinary form, camera shake, and brief occlusion.",
  "Ignore setup, getting into position, rest breaks, standing or sitting gaps, and motion after the hold ends.",
  "If the hold is briefly broken, count only the continuous valid hold segments and sum them.",
  "Measure original-time seconds of the valid hold. Do not estimate from repetition-like bouncing.",
  "Return zero only when no measurable plank hold is visible.",
].join(" ");

const outdoorLatPulldownPrompt = [
  "Count seated outdoor bodyweight lat-pulldown machine repetitions in casual phone videos.",
  "The person sits at an outdoor machine and repeatedly pulls a bar or handles downward, then returns upward.",
  "Count each complete pull-down and return cycle once.",
  "The whole body does not need to be visible; repeated arm, shoulder, and torso travel of the seated pulling motion is sufficient.",
  "Tolerate imperfect framing, ordinary form, incomplete range, brief occlusion, and camera shake.",
  "Ignore setup, seat adjustment, unrelated movement, cuts, loops, and motion before or after the repetition sequence.",
  "Identify the exercise segment, track successive high and low handle or arm positions chronologically, and count each return from a low position to a high position as one cycle.",
  "Before answering, scan the full exercise segment a second time and reconcile the cycle count; do not estimate from elapsed time.",
  "Return zero only when no lat-pulldown repetition cycles are visible.",
].join(" ");

const densifyInstruction = [
  `The supplied video is temporally densified by a factor of ${DENSIFY_FACTOR}:`,
  "four frames from each original second are shown as four successive playback seconds.",
  "For a plank, return the measured duration in densified playback seconds; do not convert it to original time.",
  "The server will perform the deterministic duration conversion after your response.",
  "Repetition counts are not scaled.",
].join(" ");

export const classifierPrompt = [
  "Classify and measure one exercise in casual phone videos.",
  densifyInstruction,
  "Choose exactly one exercise_id: push_up, plank, outdoor_lat_pulldown, or unsupported.",
  "Use unsupported when the clip is unrelated, mixed, unrecognizable, empty of exercise motion, or shows more than one distinct exercise.",
  "When unsupported, set metric to none and value to 0.",
  "When push_up, metric is repetitions.",
  pushupPrompt,
  "When plank, metric is hold_seconds.",
  plankPrompt,
  "When outdoor_lat_pulldown, metric is repetitions.",
  outdoorLatPulldownPrompt,
  "Return the raw measured value only. Do not convert the measurement into sets, minutes, or a pass/fail judgment.",
].join(" ");

export const classifierUserPrompt = "Classify the preceding video and return the raw measured value for the recognized exercise.";

const definitions = Object.freeze({
  push_up: Object.freeze({
    id: "push_up",
    displayName: "Push-ups",
    metric: ExerciseMetric.REPETITIONS,
    resultField: "repetition_count",
    schemaName: "pushup_count",
    unitsPerSet: 5,
    systemPrompt: pushupPrompt,
    userPrompt: "Count the repetitions in the preceding video.",
    pending: Object.freeze([]),
  }),
  plank: Object.freeze({
    id: "plank",
    displayName: "Plank",
    metric: ExerciseMetric.HOLD_SECONDS,
    resultField: "hold_seconds",
    schemaName: "plank_hold",
    unitsPerSet: 18,
    systemPrompt: plankPrompt,
    userPrompt: "Measure the plank hold in the preceding video.",
    pending: Object.freeze([]),
  }),
  outdoor_lat_pulldown: Object.freeze({
    id: "outdoor_lat_pulldown",
    displayName: "Outdoor lat pull-down",
    metric: ExerciseMetric.REPETITIONS,
    resultField: "repetition_count",
    schemaName: "outdoor_lat_pulldown_count",
    unitsPerSet: 10,
    systemPrompt: outdoorLatPulldownPrompt,
    userPrompt: "Count the repetitions in the preceding video.",
    pending: Object.freeze([]),
  }),
  sit_up: Object.freeze({
    id: "sit_up",
    displayName: "Sit-ups",
    metric: ExerciseMetric.REPETITIONS,
    resultField: "repetition_count",
    schemaName: "situp_count",
    unitsPerSet: null,
    systemPrompt: null,
    userPrompt: null,
    pending: Object.freeze(["example_video", "recognition_prompt", "qualifying_repetitions", "reward_policy"]),
  }),
});

export function getExerciseDefinition(id) {
  return definitions[id] ?? null;
}

export function exerciseIsReady(definition) {
  return definition !== null &&
    definition.systemPrompt !== null &&
    definition.userPrompt !== null &&
    definition.unitsPerSet !== null &&
    definition.pending.length === 0;
}

export function expectedMetricForExercise(exerciseId) {
  if (exerciseId === "unsupported") return ExerciseMetric.NONE;
  return getExerciseDefinition(exerciseId)?.metric ?? null;
}

export function publicExerciseCatalog() {
  return Object.values(definitions).map((definition) => ({
    id: definition.id,
    display_name: definition.displayName,
    metric: definition.metric,
    units_per_set: definition.unitsPerSet,
    ready: exerciseIsReady(definition),
    pending: [...definition.pending],
  }));
}
