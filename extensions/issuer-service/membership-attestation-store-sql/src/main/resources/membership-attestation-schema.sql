CREATE TABLE IF NOT EXISTS membership_attestation
(
    membership_type       varchar                             not null,
    holder_id             varchar                             not null,
    name                  varchar                             not null,
    membership_start_date timestamp default now()             not null,
    groups                varchar   default '[]'              not null,
    id                    varchar   default gen_random_uuid() not null
        constraint attestations_pk
            primary key
);

