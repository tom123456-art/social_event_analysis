# Single-Account Migration

1. Stop the old backend. Back up `social_hotspot_analytics` with `mysqldump --single-transaction --routines --triggers --no-tablespaces` and check its exit status before proceeding.
2. Run `20261103_single_account.sql` against that database using MySQL 8.0.16 or later.
3. Deploy the updated backend and frontend together. Log in using the existing `admin` password.

The migration preserves the admin ID and password, keeps AI conversations, and removes extra login accounts and all sessions. Historical comment/submission usernames remain intact; references to removed accounts become NULL. `app_user.role` is removed. A checked, unique `account_slot` prevents inserting a second account. AI message `role` values describe message speakers and are unchanged.

MySQL DDL cannot be rolled back as part of the DML transaction. The migration is rerunnable. To undo it, stop the new backend, restore the verified database backup, and restore the matching old application version.

For a new installation, use `deploy/sql/schema.sql`, not this migration. Set `AUTH_BOOTSTRAP_ADMIN_PASSWORD` before first startup; it only initializes missing credentials and does not overwrite an existing password.

Business routes require authentication; only `POST /api/auth/login` is public. Registration and account CRUD are removed. Analysis routes are `/api/analysis/{dashboard,keyword-analysis,sentiment-analysis,trend-content-rank,propagation-evidence,content-page}`. The dataset ID is internal. Profile GET and PUT both use `/api/front/profile`; PUT accepts only nickname, phone, email and bio. Review authors come from the authenticated principal.
