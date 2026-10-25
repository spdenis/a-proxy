# Regions — run order and windows

REPLACE THE EXAMPLE TIMES WITH YOUR STAGING SCHEDULE.

| Order | Region | Batch window (UTC) | Hard cut-off (UTC) | Notes |
|---|---|---|---|---|
| 1 | TOKYO | 15:00 – 21:30 | 22:00 | Must finish before EMEA start-of-day jobs |
| 2 | EMEA  | 22:00 – 05:30 | 06:00 | Depends on TOKYO positions |
| 3 | NAM   | 21:00 – 02:30 | 04:00 | Runs partly in parallel with EMEA |

## Cross-region dependencies

- EMEA batches read TOKYO end-of-day positions → a stuck TOKYO batch will block EMEA.
- (add yours)

## Calendar

- Staging follows the production holiday calendars per region; a region batch may legitimately not run on its local holiday.
- Weekends: (describe what runs)
