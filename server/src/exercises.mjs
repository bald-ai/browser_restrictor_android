export const ExerciseMetric = Object.freeze({
  REPETITIONS: "repetitions",
  HOLD_SECONDS: "hold_seconds",
});

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

const definitions = Object.freeze({
  push_up: Object.freeze({
    id: "push_up",
    displayName: "Push-ups",
    metric: ExerciseMetric.REPETITIONS,
    resultField: "repetition_count",
    schemaName: "pushup_count",
    minimumSuccessValue: 5,
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
    minimumSuccessValue: 25,
    systemPrompt: null,
    userPrompt: null,
    pending: Object.freeze(["example_video", "recognition_prompt", "reward_policy"]),
  }),
  outdoor_lat_pulldown: Object.freeze({
    id: "outdoor_lat_pulldown",
    displayName: "Outdoor lat pull-down",
    metric: ExerciseMetric.REPETITIONS,
    resultField: "repetition_count",
    schemaName: "outdoor_lat_pulldown_count",
    minimumSuccessValue: null,
    systemPrompt: null,
    userPrompt: null,
    pending: Object.freeze(["example_video", "recognition_prompt", "qualifying_repetitions", "reward_policy"]),
  }),
  sit_up: Object.freeze({
    id: "sit_up",
    displayName: "Sit-ups",
    metric: ExerciseMetric.REPETITIONS,
    resultField: "repetition_count",
    schemaName: "situp_count",
    minimumSuccessValue: null,
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
    definition.minimumSuccessValue !== null &&
    definition.pending.length === 0;
}

export function publicExerciseCatalog() {
  return Object.values(definitions).map((definition) => ({
    id: definition.id,
    display_name: definition.displayName,
    metric: definition.metric,
    minimum_success_value: definition.minimumSuccessValue,
    ready: exerciseIsReady(definition),
    pending: [...definition.pending],
  }));
}

