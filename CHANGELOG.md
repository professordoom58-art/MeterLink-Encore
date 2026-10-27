# Changelog

## Field-report fixes v2

- Daily load surveys retain up to 400 rows because the full buffer is already downloaded; block-load surveys retain the latest 48 rows.
- Load-survey reports show the number of displayed rows when local trimming occurs.
- Meter wall-clock timestamps, event-log details, profile labels, and diagnostic exports were corrected for field-report compatibility.
- Daily range reads now cover complete meter days, fall through on empty selective results, and expose Recent, Last 30 Days, and Full Buffer selectors.
- Hex exports are chronological and coalesce RX chunks; PDF filenames resolve the live serial and timestamp at export time.

### Assumptions and TODOs

- `// TODO: confirm code 65 against L&T manual` remains on the billing reset-type path; code 65 is printed unchanged.
- `// TODO: confirm with L&T documentation` remains on the vendor block-load value label.
