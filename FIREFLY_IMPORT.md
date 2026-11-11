# Export Pocket Ledger to Firefly III

In Pocket Ledger, open **Settings → Export for Firefly III**. Save the CSV anywhere you choose. This exports expenses, refunds, and incoming payments, including your purchase names and categories. Categories marked Exclude from spending are skipped because a real transfer requires a destination asset account, which Pocket Ledger does not store. Ordinary CSV keeps all transactions and includes Type, Spending effect CNY, and Counts as spending columns. Amount CNY is the positive magnitude; Spending effect CNY is positive for expenses, negative for refunds, and zero for income/excluded categories. Unknown balances export blank. You can do this before installing Firefly III. The app does not connect to a server or ask for an API token.

When Firefly III and its separate Data Importer are ready:

1. Create or select a CNY asset account for each bank card.
2. In Data Importer, choose CSV and upload the exported file. Use comma delimiter, a header row, and date format `Y-m-d H:i:s`. Choose the phone's timezone for the import.
3. Assign the roles below. Map each card label to the corresponding asset account. You can also map categories to your existing Firefly categories.
4. Review the preview and spending totals, then confirm the import. Save the importer's configuration for later exports.

| CSV column | Data Importer role |
| --- | --- |
| Date | Transaction date |
| Description | Description |
| Amount | Amount |
| Currency | Currency code |
| Asset account | Asset account name |
| Opposing account | Opposing account name |
| Category | Category name |
| External ID | External ID |

These roles and the upload/mapping workflow follow Firefly III's [field-role reference](https://github.com/firefly-iii/docs/blob/main/docs/docs/references/data-importer/roles.md) and [CSV tutorial](https://github.com/firefly-iii/docs/blob/main/docs/docs/tutorials/data-importer/csv.md).

Pocket Ledger writes expenses as negative decimal amounts, such as `-28.50`, and refunds/incoming payments as positive amounts, with currency `CNY`. Keep the Amount column signed; do not choose the negated-amount role. `Bank of Communications 1234` identifies the card ending 1234. `Unknown merchant` is a placeholder opposing account for expenses/refunds; review its mapping and the resulting credit direction in the importer preview. A positive refund may be treated as a deposit/revenue by Firefly rather than reducing an original expense; this CSV does not guarantee the same net-spending treatment as Pocket Ledger. `Unknown income` is the placeholder source for incoming payments; map it to the appropriate revenue account. The SMS may not identify a unique counterparty. Account labels use the recorded card/account suffix. Refunds are separate dated credit records, not edits to original purchases. Renaming a purchase changes its Description and does not claim to identify the merchant.

Keep duplicate detection enabled. Each export retains the same `pocket-ledger:` External ID for the same original purchase, even after renaming or recategorizing it. Firefly III can use these IDs when checking duplicates, but behavior depends on importer configuration; review repeated imports carefully. See its [duplicate-detection guide](https://github.com/firefly-iii/docs/blob/main/docs/docs/how-to/data-importer/import/duplicates.md).

CSV export is a snapshot, not synchronization. Renaming or recategorizing in Pocket Ledger does not update records already imported into Firefly III. Make corresponding edits there when needed. Deleted purchases and categories excluded from spending are omitted from future Firefly CSV files; previous Firefly entries are not deleted. Changing transaction type after an earlier Firefly import also does not update that imported record automatically. No balance, raw SMS, budget, or category-rule synchronization is included.

The CSV writer is covered by automated tests for signed CNY values, column order, escaping, stable IDs, renamed purchases, and Chinese phone locale formatting. A live Firefly III import has not been tested because no server is available yet.
