CREATE SEQUENCE IF NOT EXISTS cultural_facility_id_seq
    AS INTEGER
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE IF NOT EXISTS cultural_facility (
    id INTEGER NOT NULL DEFAULT nextval('cultural_facility_id_seq'::regclass),
    geom geometry(Point, 4326),
    name VARCHAR(254),
    category VARCHAR(254),
    latitude NUMERIC(23, 15),
    longitude NUMERIC(23, 15),
    address VARCHAR(254),
    telephone VARCHAR(254),
    homepage VARCHAR(254),
    holiday VARCHAR(254),
    onhour VARCHAR(254),
    parklot VARCHAR(254),
    usage_fee VARCHAR(254),
    pet_availa VARCHAR(254),
    pet_size VARCHAR(254),
    registrati VARCHAR(254),
    descriptio VARCHAR(254),
    extra_fee VARCHAR(254),

    CONSTRAINT cultural_facility_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE cultural_facility_id_seq OWNED BY cultural_facility.id;
