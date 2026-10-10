USE social_hotspot_analytics;

START TRANSACTION;
-- Historical import metadata is retained, but it is not a selectable dataset.
UPDATE event_info
SET status = 'ARCHIVED',
    event_name = 'Historical import (social_event_real)'
WHERE event_id = 'social_event_real';

-- A hex literal avoids Chinese text being damaged by a shell/client encoding.
UPDATE event_info
SET event_name = CONVERT(0xE7A4BEE4BAA4E5AA92E4BD93E783ADE782B9E4BA8BE4BBB6E4BCA0E692ADE58886E69E90 USING utf8mb4)
WHERE event_id = 'public_rss_latest';
COMMIT;
