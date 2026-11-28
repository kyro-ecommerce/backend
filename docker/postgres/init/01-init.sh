#!/bin/bash
set -e

echo "Creating databases..."
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE kyro_auth;
    CREATE DATABASE kyro_catalog;
    CREATE DATABASE kyro_cart;
    CREATE DATABASE kyro_order;
    CREATE DATABASE kyro_payment;
    CREATE DATABASE ai_service;
EOSQL

echo "Databases created successfully."
