#!/usr/bin/env bash
# Prepares the PostgreSQL of the database server (the one set up with ROLE=db by the TOSEM setup-server.sh) for the
# starvation experiments: the "uslpool" database, a pg_hba.conf line that admits the application server for it, and
# a check that max_connections leaves room for the largest pool (56 connections, DBCP2 at N*) plus the admin pool.
# Run as root, outside any measurement window:
#
#   PEER_IP=<application server IP> bash setup-db.sh
#
# The postgres password stays the one setup-server.sh set (PG_PASSWORD); the campaign passes it as JDBC_PASSWORD.
# The firewall already admits PostgreSQL from PEER_IP (setup-server.sh). Running it again is harmless.
set -euo pipefail

: "${PEER_IP:?Set PEER_IP to the application server IP}"
PG_MAJOR="$(ls /etc/postgresql | sort -n | tail -1)"
PG_HBA="/etc/postgresql/$PG_MAJOR/main/pg_hba.conf"

if ! systemctl is-active --quiet postgresql; then
    systemctl start postgresql
fi
psql_postgres() { sudo -u postgres psql -v ON_ERROR_STOP=1 -qAt "$@"; }

if [ "$(psql_postgres -c "SELECT 1 FROM pg_database WHERE datname = 'uslpool'")" != "1" ]; then
    psql_postgres -c "CREATE DATABASE uslpool"
    echo "Created database uslpool"
fi

LINE="host    uslpool    postgres    $PEER_IP/32    scram-sha-256    # reservoir-peer"
if ! grep -qF "# reservoir-peer" "$PG_HBA"; then
    echo "$LINE" >> "$PG_HBA"
    echo "Added to $PG_HBA: $LINE"
elif ! grep -qF "$LINE" "$PG_HBA"; then
    sed -i "/# reservoir-peer/c\\$LINE" "$PG_HBA"
    echo "Updated in $PG_HBA: $LINE"
fi
systemctl reload postgresql

MAX="$(psql_postgres -c "SHOW max_connections")"
echo "max_connections = $MAX, listen_addresses = $(psql_postgres -c "SHOW listen_addresses")"
if [ "$MAX" -lt 80 ]; then
    echo "max_connections is below 80: the largest pool (56) and the admin pool may not fit" >&2
    exit 1
fi
echo "PostgreSQL $PG_MAJOR is ready for the starvation block"
