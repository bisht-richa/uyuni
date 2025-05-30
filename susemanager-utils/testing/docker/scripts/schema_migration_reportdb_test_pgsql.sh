#!/bin/bash

set -e

echo "============================================================"
echo "                      Migration test ReportDB               "
echo "============================================================"

SCRIPT=$(basename ${0})
usage_and_exit() {
    echo "Usage: ${1} schema_rpms"
    exit 2
}

if [ ${#} -ne 1 ];then
    echo "Missing parameters"
    usage_and_exit ${SCRIPT}
fi

schema_rpms=${1}

for i in ${schema_rpms};do
    if [ ! -f /root/${i} ];then
        echo "RPM /root/${i} does not exists"
        usage_and_exit ${SCRIPT}
    fi
done

cd /manager/susemanager-utils/testing/docker/scripts/

# Move Postgres database to tmpfs to speed initialization and testing up
if [ ! -z $PG_TMPFS_DIR ]; then
    trap "umount $PG_TMPFS_DIR" EXIT INT TERM
    ./docker-testing-pgsql-move-data-to-tmpfs.sh $PG_TMPFS_DIR
fi

# Database schema creation

pushd /root
rpm -ihv ${schema_rpms}
popd

export PERLLIB=/manager/spacewalk/setup/lib/:/manager/web/modules/rhn/:/manager/web/modules/pxt/:/manager/schema/spacewalk/lib
export PATH=/manager/schema/spacewalk/:/manager/spacewalk/setup/bin/:$PATH

echo Going to reset pgsql database

echo $PATH
echo $PERLLIB

export SYSTEMD_NO_WRAP=1
su - postgres -c "/usr/lib/postgresql/bin/pg_ctl stop" ||:
su - postgres -c "/usr/lib/postgresql/bin/pg_ctl start"

touch /var/lib/rhn/rhn-satellite-prep/etc/rhn/rhn.conf
# SUSE Manager initialization
cp /root/rhn.conf /etc/rhn/rhn.conf
smdba system-check autotuning --max_connections=50


# we changed the schema dir, but we start with a schema which live still in the old location
# provide a symlink to make the tooling work
if [ -d /etc/sysconfig/rhn/postgres -a ! -e /usr/share/susemanager/db/postgres ]; then
    mkdir -p /usr/share/susemanager/db
    ln -s /etc/sysconfig/rhn/postgres /usr/share/susemanager/db/postgres
    ln -s /etc/sysconfig/rhn/reportdb /usr/share/susemanager/db/reportdb
fi

# We need SUPERUSER role to install the old schema as they add extensions.
# This basically reproduces the upgrade from an existing DB setup.
su - postgres -c "echo 'ALTER ROLE pythia WITH SUPERUSER;' | psql -d reportdb"

spacewalk-sql --reportdb /usr/share/susemanager/db/reportdb/main.sql

# this copy the latest schema from the git into the system
./build-reportdb-schema.sh

RPMVERSION=`rpm -q --qf "%{version}\n" --specfile /manager/schema/reportdb/uyuni-reportdb-schema.spec | head -n 1`
NEXTVERSION=`echo $RPMVERSION | awk '{ pre=post=$0; gsub("[0-9]+$","",pre); gsub(".*\\\\.","",post); print pre post+1; }'`

if [ -d /usr/share/susemanager/db/reportdb-schema-upgrade/uyuni-reportdb-schema-$RPMVERSION-to-uyuni-reportdb-schema-$NEXTVERSION ]; then
    export SUMA_TEST_SCHEMA_VERSION=$NEXTVERSION

else
    export SUMA_TEST_SCHEMA_VERSION=$RPMVERSION
fi

# set hard destination schema migration version with this VAR
#export SUMA_TEST_SCHEMA_VERSION="4.3.0"

# run the schema upgrade from git repo
if ! /manager/schema/spacewalk/spacewalk-schema-upgrade -y --reportdb; then
    cat /var/log/spacewalk/reportdb-schema-upgrade/schema-from-*.log
    su - postgres -c "/usr/lib/postgresql/bin/pg_ctl stop"
    exit 1
fi

# Postgres shutdown (avoid stale memory by shmget())
su - postgres -c "/usr/lib/postgresql/bin/pg_ctl stop"
