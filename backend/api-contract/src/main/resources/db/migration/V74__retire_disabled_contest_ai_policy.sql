-- 2026-10-30: the "no interception" AI policy is retired. Every contest keeps its participants from
-- getting complete code, either with the existing rules (DEFAULT, STRICT) or by turning AI off for
-- them (NO_AI). Blueprints move to DEFAULT; published runs keep the snapshot they were published with.
UPDATE contests SET ai_policy_mode = 'DEFAULT' WHERE ai_policy_mode = 'DISABLED';
