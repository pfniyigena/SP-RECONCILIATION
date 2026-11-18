-- V1 baseline: the production schema as of 2026-11-17 (before release 2.1.0).
-- On the existing production database Flyway does NOT run this file (baseline-on-migrate marks the
-- database as already at version 1). It runs only on an EMPTY database, to build the same schema.
-- Includes tables that belong to other modules of the wider system (application_module, card_information,
-- card_recharge, help_ticket): they exist in production, so the baseline keeps them. Never edit this file;
-- add V3, V4... for further changes.

--
-- PostgreSQL database dump
--


-- Dumped from database version 14.24 (Ubuntu 14.24-1.pgdg22.04+2)
-- Dumped by pg_dump version 16.15; cleaned for Flyway (psql meta-commands and search_path reset removed)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

-- *not* creating schema, since initdb creates it


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: app_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255) NOT NULL,
    modified_at timestamp(6) without time zone,
    updated_by character varying(255) NOT NULL,
    version integer NOT NULL,
    enabled boolean,
    full_name character varying(255),
    password character varying(255) NOT NULL,
    role character varying(255) NOT NULL,
    username character varying(255) NOT NULL
);


--
-- Name: app_user_customer_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user_customer_access (
    app_user_id uuid NOT NULL,
    customer_id uuid NOT NULL
);


--
-- Name: application_module; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.application_module (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255) NOT NULL,
    modified_at timestamp(6) without time zone,
    updated_by character varying(255) NOT NULL,
    version integer NOT NULL,
    enabled boolean,
    name character varying(255),
    url character varying(255)
);


--
-- Name: card_information; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.card_information (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    modified_at timestamp(6) without time zone,
    updated_by character varying(255),
    version integer NOT NULL,
    card_number character varying(255),
    current_balance numeric(38,2),
    customer_id uuid
);


--
-- Name: card_recharge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.card_recharge (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    modified_at timestamp(6) without time zone,
    updated_by character varying(255),
    version integer NOT NULL,
    address_description character varying(255),
    address_id integer,
    address_name character varying(255),
    card_number character varying(255),
    client_id integer,
    client_name character varying(255),
    date_time_txs timestamp(6) without time zone,
    plate_number character varying(255),
    pos_id integer,
    pos_name character varying(255),
    pos_number integer,
    processed boolean,
    quantity numeric(38,2),
    sdc_id character varying(255),
    service_id integer,
    service_name character varying(255),
    stamp_data character varying(255),
    success boolean,
    tag_number character varying(255),
    total_amount numeric(38,2),
    trn_guid character varying(255),
    unit_price numeric(38,2),
    customer_id uuid,
    machine_id uuid
);


--
-- Name: card_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.card_transaction (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255) NOT NULL,
    modified_at timestamp(6) without time zone,
    updated_by character varying(255) NOT NULL,
    version integer NOT NULL,
    address_description character varying(255),
    address_id integer,
    address_name character varying(255),
    card_number character varying(255),
    client_id integer,
    client_name character varying(255),
    date_time_txs timestamp(6) without time zone,
    plate_number character varying(255),
    pos_id integer,
    pos_name character varying(255),
    pos_number integer,
    quantity numeric(38,2),
    service_id integer,
    service_name character varying(255),
    tag_number character varying(255),
    total_amount numeric(38,2),
    trn_guid character varying(255),
    unit_price numeric(38,2),
    processed boolean,
    sdc_id character varying(255),
    stamp_data character varying(255),
    success boolean,
    machine_id uuid,
    customer_id uuid,
    ebm_data character varying(255),
    allocated boolean,
    reconciliation_id uuid
);


--
-- Name: customer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    modified_at timestamp(6) without time zone,
    updated_by character varying(255),
    version integer NOT NULL,
    client_id integer,
    client_name character varying(255),
    client_tin character varying(255),
    enabled boolean,
    allocated boolean
);


--
-- Name: customer_deposit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_deposit (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    modified_at timestamp(6) without time zone,
    updated_by character varying(255),
    version integer NOT NULL,
    client_id integer,
    client_name character varying(255),
    date_time_txs timestamp(6) without time zone,
    processed boolean,
    sap_reference character varying(255),
    service_id integer,
    service_name character varying(255),
    stamp_data character varying(255),
    total_amount numeric(38,2),
    trn_guid character varying(255),
    customer_id uuid,
    current_balance numeric(38,2)
);


--
-- Name: deposit_card_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.deposit_card_transaction (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    modified_at timestamp(6) without time zone,
    updated_by character varying(255),
    version integer NOT NULL,
    allocated_amount numeric(38,2),
    customer_id uuid NOT NULL,
    deposit_id uuid NOT NULL,
    card_transaction_id uuid NOT NULL
);


--
-- Name: help_ticket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.help_ticket (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255) NOT NULL,
    modified_at timestamp(6) without time zone,
    updated_by character varying(255) NOT NULL,
    version integer NOT NULL,
    comment character varying(255),
    duration bigint,
    end_date timestamp(6) without time zone,
    note character varying(255),
    start_date timestamp(6) without time zone,
    ticket_status character varying(255),
    ticket_type character varying(255),
    institution uuid,
    CONSTRAINT help_ticket_ticket_status_check CHECK (((ticket_status)::text = ANY ((ARRAY['PENDING'::character varying, 'OPEN'::character varying, 'CLOSE'::character varying])::text[]))),
    CONSTRAINT help_ticket_ticket_type_check CHECK (((ticket_type)::text = ANY ((ARRAY['INSTALLATION'::character varying, 'UPGRADE'::character varying, 'SUPPORT'::character varying, 'MAINTENANCE'::character varying])::text[])))
);


--
-- Name: institution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.institution (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255) NOT NULL,
    modified_at timestamp(6) without time zone,
    updated_by character varying(255) NOT NULL,
    version integer NOT NULL,
    enabled boolean,
    name character varying(255),
    tin_number character varying(255) NOT NULL
);


--
-- Name: reconciliation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reconciliation (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    modified_at timestamp(6) without time zone,
    updated_by character varying(255),
    version integer NOT NULL,
    reconciliation_date date NOT NULL,
    reference character varying(255)
);


--
-- Name: stamp_machine; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stamp_machine (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255) NOT NULL,
    modified_at timestamp(6) without time zone,
    updated_by character varying(255) NOT NULL,
    version integer NOT NULL,
    enabled boolean,
    location character varying(255),
    sdc_id character varying(255),
    institution_id uuid,
    model character varying(255),
    serial_number character varying(255)
);


--
-- Name: tax_reporter_invoice; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tax_reporter_invoice (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255) NOT NULL,
    modified_at timestamp(6) without time zone,
    updated_by character varying(255) NOT NULL,
    version integer NOT NULL,
    client_name character varying(255),
    client_phone character varying(255),
    client_tin character varying(255),
    erp_code character varying(255),
    paid_amount numeric(38,2),
    payment_mode character varying(255),
    plate_number character varying(255),
    receipt_number bigint,
    registered_name character varying(255),
    registered_tin character varying(255),
    sdc_id character varying(255),
    stamp_data character varying(255),
    stamp_date timestamp(6) without time zone,
    total_amount numeric(38,2),
    total_tax_amount numeric(38,2),
    transaction_type character varying(255),
    processed boolean,
    failure_reason text
);


--
-- Name: terminal_machine; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.terminal_machine (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255) NOT NULL,
    modified_at timestamp(6) without time zone,
    updated_by character varying(255) NOT NULL,
    version integer NOT NULL,
    address_description character varying(255),
    address_name character varying(255),
    enabled boolean,
    location character varying(255),
    pos_id integer,
    pos_name character varying(255),
    pos_number integer,
    sdc_id character varying(255),
    stamp_machine_id uuid
);


--
-- Name: app_user_customer_access app_user_customer_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user_customer_access
    ADD CONSTRAINT app_user_customer_access_pkey PRIMARY KEY (app_user_id, customer_id);


--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: application_module application_module_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_module
    ADD CONSTRAINT application_module_pkey PRIMARY KEY (id);


--
-- Name: card_information card_information_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_information
    ADD CONSTRAINT card_information_pkey PRIMARY KEY (id);


--
-- Name: card_recharge card_recharge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_recharge
    ADD CONSTRAINT card_recharge_pkey PRIMARY KEY (id);


--
-- Name: card_transaction card_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_transaction
    ADD CONSTRAINT card_transaction_pkey PRIMARY KEY (id);


--
-- Name: customer_deposit customer_deposit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_deposit
    ADD CONSTRAINT customer_deposit_pkey PRIMARY KEY (id);


--
-- Name: customer customer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer
    ADD CONSTRAINT customer_pkey PRIMARY KEY (id);


--
-- Name: deposit_card_transaction deposit_card_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_card_transaction
    ADD CONSTRAINT deposit_card_transaction_pkey PRIMARY KEY (id);


--
-- Name: help_ticket help_ticket_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.help_ticket
    ADD CONSTRAINT help_ticket_pkey PRIMARY KEY (id);


--
-- Name: institution institution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.institution
    ADD CONSTRAINT institution_pkey PRIMARY KEY (id);


--
-- Name: reconciliation reconciliation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reconciliation
    ADD CONSTRAINT reconciliation_pkey PRIMARY KEY (id);


--
-- Name: stamp_machine stamp_machine_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stamp_machine
    ADD CONSTRAINT stamp_machine_pkey PRIMARY KEY (id);


--
-- Name: tax_reporter_invoice tax_reporter_invoice_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tax_reporter_invoice
    ADD CONSTRAINT tax_reporter_invoice_pkey PRIMARY KEY (id);


--
-- Name: terminal_machine terminal_machine_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.terminal_machine
    ADD CONSTRAINT terminal_machine_pkey PRIMARY KEY (id);


--
-- Name: card_transaction uk15ctwtkeghx4ml0hiakti34q3; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_transaction
    ADD CONSTRAINT uk15ctwtkeghx4ml0hiakti34q3 UNIQUE (trn_guid);


--
-- Name: app_user uk3k4cplvh82srueuttfkwnylq0; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT uk3k4cplvh82srueuttfkwnylq0 UNIQUE (username);


--
-- Name: tax_reporter_invoice uk9r8km2vpsxp53pomfngch73ps; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tax_reporter_invoice
    ADD CONSTRAINT uk9r8km2vpsxp53pomfngch73ps UNIQUE (stamp_data);


--
-- Name: customer_deposit ukb2b6yp70n1897pp96caefw8np; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_deposit
    ADD CONSTRAINT ukb2b6yp70n1897pp96caefw8np UNIQUE (trn_guid);


--
-- Name: card_recharge ukf5ox6odtwyfqtekb9ojsrme87; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_recharge
    ADD CONSTRAINT ukf5ox6odtwyfqtekb9ojsrme87 UNIQUE (trn_guid);


--
-- Name: customer ukf6b3v1v7hs07485xj57p5wfgd; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer
    ADD CONSTRAINT ukf6b3v1v7hs07485xj57p5wfgd UNIQUE (client_name);


--
-- Name: stamp_machine ukh1chtu0914e6y9bs9pcpre9xr; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stamp_machine
    ADD CONSTRAINT ukh1chtu0914e6y9bs9pcpre9xr UNIQUE (serial_number);


--
-- Name: stamp_machine ukh28xpmhfr6w998ihyfsc9t33l; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stamp_machine
    ADD CONSTRAINT ukh28xpmhfr6w998ihyfsc9t33l UNIQUE (sdc_id);


--
-- Name: idx_card_transaction_sdc_unprocessed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_card_transaction_sdc_unprocessed ON public.card_transaction USING btree (sdc_id) WHERE (processed = false);


--
-- Name: help_ticket fk13h9l389dhwnkug2y9uqm0jwk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.help_ticket
    ADD CONSTRAINT fk13h9l389dhwnkug2y9uqm0jwk FOREIGN KEY (institution) REFERENCES public.institution(id);


--
-- Name: card_information fk162wb435p0wjvtm8kfwt4nioe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_information
    ADD CONSTRAINT fk162wb435p0wjvtm8kfwt4nioe FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: card_transaction fk1ekg1hjxrpx523m7lgcq12t31; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_transaction
    ADD CONSTRAINT fk1ekg1hjxrpx523m7lgcq12t31 FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: app_user_customer_access fk4wr0c951fi9aq0pe6toy4g7av; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user_customer_access
    ADD CONSTRAINT fk4wr0c951fi9aq0pe6toy4g7av FOREIGN KEY (app_user_id) REFERENCES public.app_user(id);


--
-- Name: customer_deposit fk6uc7h82bsf2igxksuif9haork; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_deposit
    ADD CONSTRAINT fk6uc7h82bsf2igxksuif9haork FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: deposit_card_transaction fk7yvruvtcmteax6fgkd1plq5rb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_card_transaction
    ADD CONSTRAINT fk7yvruvtcmteax6fgkd1plq5rb FOREIGN KEY (card_transaction_id) REFERENCES public.card_transaction(id);


--
-- Name: terminal_machine fk8fmflidiuu2mlsiuy3crmkdvm; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.terminal_machine
    ADD CONSTRAINT fk8fmflidiuu2mlsiuy3crmkdvm FOREIGN KEY (stamp_machine_id) REFERENCES public.stamp_machine(id);


--
-- Name: deposit_card_transaction fkc1pggodlht61rfbaqf1rujb09; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_card_transaction
    ADD CONSTRAINT fkc1pggodlht61rfbaqf1rujb09 FOREIGN KEY (deposit_id) REFERENCES public.customer_deposit(id);


--
-- Name: app_user_customer_access fkjyibdcqn040wf5omcwyatbheg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user_customer_access
    ADD CONSTRAINT fkjyibdcqn040wf5omcwyatbheg FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: stamp_machine fkmwbsocc6w4ukw44clr1dqokpf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stamp_machine
    ADD CONSTRAINT fkmwbsocc6w4ukw44clr1dqokpf FOREIGN KEY (institution_id) REFERENCES public.institution(id);


--
-- Name: card_recharge fko9vbh701cvofg5evgb1uiq3e0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_recharge
    ADD CONSTRAINT fko9vbh701cvofg5evgb1uiq3e0 FOREIGN KEY (machine_id) REFERENCES public.terminal_machine(id);


--
-- Name: card_recharge fkq2wcxp4y4n0p2ry84sufffymj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_recharge
    ADD CONSTRAINT fkq2wcxp4y4n0p2ry84sufffymj FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: card_transaction fksgnnwis7q8o2mebnt98897l6l; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_transaction
    ADD CONSTRAINT fksgnnwis7q8o2mebnt98897l6l FOREIGN KEY (reconciliation_id) REFERENCES public.reconciliation(id);


--
-- Name: deposit_card_transaction fksuww3uesimdal49pk05390cqk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_card_transaction
    ADD CONSTRAINT fksuww3uesimdal49pk05390cqk FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: card_transaction fktqgrq7phoipvhf317otexw31a; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.card_transaction
    ADD CONSTRAINT fktqgrq7phoipvhf317otexw31a FOREIGN KEY (machine_id) REFERENCES public.terminal_machine(id);


--
-- PostgreSQL database dump complete
--


