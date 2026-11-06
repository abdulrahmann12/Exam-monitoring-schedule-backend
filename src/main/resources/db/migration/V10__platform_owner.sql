-- V10: platform owner (Phase 2, step 6)
--   * new role SUPER_ADMIN: the owner of the platform, who runs the owner panel
--   * the owner belongs to no university; every other account must belong to one
alter table admin_users modify column role enum ('ADMIN','SCHEDULER','VIEWER','DEMO_ADMIN','SUPER_ADMIN') not null;

-- V9 already gave every account a university; repeated here so the rule below can never fail on old rows.
update admin_users set university_id = 0x00000000000000000000000000000001 where university_id is null;

alter table admin_users add constraint ck_admin_users_owner_has_no_university check (
    (role = 'SUPER_ADMIN' and university_id is null) or (role <> 'SUPER_ADMIN' and university_id is not null)
);
