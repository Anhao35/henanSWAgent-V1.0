# MITRE local validation catalogs

These compact catalogs are used only to verify model-produced identifiers and to
replace model-produced names with canonical names. They do not contain descriptions.

- `attack-catalog.tsv`: active Enterprise ATT&CK techniques, sub-techniques, and tactics,
  generated from MITRE's `attack-stix-data` Enterprise bundle.
- `cwe-catalog.tsv`: CWE identifiers and names from the MITRE CWE Research Concepts
  view (1000 CSV).

Sources:

- https://github.com/mitre-attack/attack-stix-data
- https://cwe.mitre.org/data/downloads.html

The catalogs were refreshed on 2026-09-03. Refresh them before a production release
when MITRE publishes a newer data version.
