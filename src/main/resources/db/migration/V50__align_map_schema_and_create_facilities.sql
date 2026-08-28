ALTER SEQUENCE network_node_id_seq
    AS BIGINT
    NO MAXVALUE;

ALTER TABLE network_node
    ALTER COLUMN id TYPE BIGINT,
    ALTER COLUMN id SET DEFAULT nextval('network_node_id_seq'::regclass);

ALTER SEQUENCE network_node_id_seq OWNED BY network_node.id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'network_link'
          AND column_name = 'class'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'network_link'
          AND column_name = 'fclass'
    ) THEN
        ALTER TABLE network_link RENAME COLUMN class TO fclass;
    END IF;
END
$$;

ALTER TABLE network_link
    ADD COLUMN IF NOT EXISTS fclass VARCHAR(28),
    ADD COLUMN IF NOT EXISTS obstructio NUMERIC,
    ADD COLUMN IF NOT EXISTS green NUMERIC,
    ADD COLUMN IF NOT EXISTS slope NUMERIC(10, 3),
    ADD COLUMN IF NOT EXISTS walk_cost_ NUMERIC(23, 15),
    ADD COLUMN IF NOT EXISTS cycle_cost NUMERIC(23, 15),
    ADD COLUMN IF NOT EXISTS slope_cost NUMERIC(15, 10),
    ADD COLUMN IF NOT EXISTS amenity BIGINT,
    ADD COLUMN IF NOT EXISTS park VARCHAR(100),
    ADD COLUMN IF NOT EXISTS water VARCHAR(100);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'network_link'
          AND column_name = 'link_id'
          AND data_type <> 'bigint'
    ) THEN
        ALTER TABLE network_link
            ALTER COLUMN link_id TYPE BIGINT USING link_id::BIGINT;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'network_link'
          AND column_name = 'length'
          AND (
              numeric_precision IS DISTINCT FROM 15
              OR numeric_scale IS DISTINCT FROM 8
          )
    ) THEN
        ALTER TABLE network_link
            ALTER COLUMN length TYPE NUMERIC(15, 8)
            USING length::NUMERIC(15, 8);
    END IF;
END
$$;

ALTER TABLE network_link
    DROP COLUMN IF EXISTS class,
    DROP COLUMN IF EXISTS cost,
    DROP COLUMN IF EXISTS small_cost,
    DROP COLUMN IF EXISTS reverse_cost,
    DROP COLUMN IF EXISTS reverse_small_cost,
    DROP COLUMN IF EXISTS x1,
    DROP COLUMN IF EXISTS y1,
    DROP COLUMN IF EXISTS x2,
    DROP COLUMN IF EXISTS y2;

CREATE SEQUENCE IF NOT EXISTS toilet_id_seq
    AS INTEGER
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 2147483647
    CACHE 1
    NO CYCLE;

CREATE TABLE IF NOT EXISTS toilet (
    id INTEGER NOT NULL DEFAULT nextval('toilet_id_seq'::regclass),
    geom geometry(Point, 4326),
    name VARCHAR(254),
    address VARCHAR(254),
    open_time VARCHAR(254),
    alarm_bell VARCHAR(254),
    cctv VARCHAR(254),
    diaper VARCHAR(254),
    update_at VARCHAR(24),
    longitude NUMERIC(23, 15),
    latitude NUMERIC(23, 15),

    CONSTRAINT toilet_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE toilet_id_seq OWNED BY toilet.id;

CREATE SEQUENCE IF NOT EXISTS poopbag_id_seq
    AS INTEGER
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 2147483647
    CACHE 1
    NO CYCLE;

CREATE TABLE IF NOT EXISTS poopbag (
    id INTEGER NOT NULL DEFAULT nextval('poopbag_id_seq'::regclass),
    geom geometry(Point, 4326),
    dong_name VARCHAR(254),
    latitude NUMERIC(23, 15),
    longitude NUMERIC(23, 15),
    details VARCHAR(254),

    CONSTRAINT poopbag_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE poopbag_id_seq OWNED BY poopbag.id;

CREATE SEQUENCE IF NOT EXISTS water_fountain_id_seq
    AS INTEGER
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 2147483647
    CACHE 1
    NO CYCLE;

CREATE TABLE IF NOT EXISTS water_fountain (
    id INTEGER NOT NULL DEFAULT nextval('water_fountain_id_seq'::regclass),
    geom geometry(Point, 4326),
    park_name VARCHAR,
    address VARCHAR,
    longitude NUMERIC,
    latitude NUMERIC,

    CONSTRAINT water_fountain_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE water_fountain_id_seq OWNED BY water_fountain.id;
