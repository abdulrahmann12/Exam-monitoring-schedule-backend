-- V14: change the sign-in email safely.
--   The new address must be confirmed with a one-time link sent to it before it replaces the old one,
--   so a typo (or someone else's address) can never take over the account via "forgot password" or SSO.
alter table account_tokens modify purpose enum ('INVITE','PASSWORD_RESET','EMAIL_CHANGE') not null;
alter table account_tokens add column new_email varchar(160) null;
