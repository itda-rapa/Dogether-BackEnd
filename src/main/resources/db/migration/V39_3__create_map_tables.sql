CREATE TABLE IF NOT EXISTS animal_hospital (
    id BIGINT NOT NULL,
    geom geometry(Point, 4326),
    approval_date DATE,
    status VARCHAR(254),
    store_name VARCHAR(254),
    address VARCHAR(254),
    phone_number NUMERIC,
    x_longitude NUMERIC(23, 15),
    y_latitude NUMERIC(23, 15),

    CONSTRAINT animal_hospital_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS animal_pharmacy (
    id BIGINT NOT NULL,
    geom geometry(Point, 4326),
    approval_date DATE,
    status VARCHAR(254),
    store_name VARCHAR(254),
    address VARCHAR(254),
    phone_number NUMERIC,
    x_longitude NUMERIC(23, 15),
    y_latitude NUMERIC(23, 15),

    CONSTRAINT animal_pharmacy_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS network_node_id_seq
    AS INTEGER
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO MAXVALUE
    CACHE 1
    NO CYCLE;

CREATE TABLE IF NOT EXISTS network_node (
    id BIGINT NOT NULL DEFAULT nextval('network_node_id_seq'::regclass),
    geom geometry(Point, 4326),
    angle NUMERIC(23, 15),
    path VARCHAR(254),
    node_id BIGINT,
    longitude NUMERIC(12, 8),
    latitude NUMERIC(12, 8),

    CONSTRAINT network_node_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE network_node_id_seq OWNED BY network_node.id;

CREATE SEQUENCE IF NOT EXISTS network_link_id_seq
    AS INTEGER
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO MAXVALUE
    CACHE 1
    NO CYCLE;

CREATE TABLE IF NOT EXISTS network_link (
    id INTEGER NOT NULL DEFAULT nextval('network_link_id_seq'::regclass),
    geom geometry(MultiLineString, 4326),
    osm_id VARCHAR(12),
    class VARCHAR(28),
    link_id NUMERIC,
    source NUMERIC,
    target NUMERIC,
    length NUMERIC(10, 3),
    cost NUMERIC(23, 15),
    small_cost NUMERIC(23, 15),
    reverse_cost NUMERIC(10, 3),
    reverse_small_cost NUMERIC(10, 3),
    x1 NUMERIC(15, 8),
    y1 NUMERIC(15, 8),
    x2 NUMERIC(15, 8),
    y2 NUMERIC(15, 8),

    CONSTRAINT network_link_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE network_link_id_seq OWNED BY network_link.id;
