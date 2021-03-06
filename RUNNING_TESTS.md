
## Running AR-JDBC's Tests

After you have built arjdbc (run rake), then you can try testing it (if you
do not build then adapter_java.jar is not put into the lib dir).  If you
change any of the .java files you will need to rebuild.

Most DB specific unit tests hide under the **test/db** directory, the files
included in the *test* directory are mostly shared test modules and helpers.

Rake tasks are loaded from **rakelib/02-test-rake**, most adapters have a
corresponding test_[adapter] task e.g. `rake test_sqlite3` that run against DB.
To check all available (test related) tasks simply `rake -T | grep test`.

If the adapter supports creating a database it will try to do so automatically
(most embed databases such as SQLite3) for some adapters (MySQL, PostgreSQL) we
do this auto-magically (see the `rake db:create` tasks), but otherwise you'll
need to setup a database dedicated for tests (using the standard tools that come
with your DB installation).

Connection parameters: database, host etc. can usually be changed from the shell
`env` for adapters where there might be no direct control over the DB
instance/configuration, e.g. for Oracle (by looking at **test/db/oracle.rb**)
one might adapt the test database configuration using :
```
export ORACLE_HOST=192.168.1.2
export ORACLE_USER=SAMPLE
export ORACLE_PASS=sample
export ORACLE_SID=MAIN
```

Tests are run by calling the rake task corresponding the database adapter being
tested, e.g. for MySQL :

    rake test_mysql TEST=test/db/mysql/rake_test.rb

Observe the **TEST** variable used to specify a single file to be used to resolve
test cases, you pick tests by matching their names as well using **TESTOPTS** :

    rake test_postgres TESTOPTS="--name=/integer/"

Since 1.3.0 we also support prepared statements, these are enabled by default (AR)
but one can easily run tests with prepared statements disabled using env vars :

    rake test_derby PREPARED_STATEMENTS=false


### ActiveRecord (Rails) Tests

We also have the ability to run our adapters against Rails ActiveRecord
tests as well.  First, make sure you have the Rails repository cloned locally:

    git clone git://github.com/rails/rails.git

If you clone Rails to the same parent directory this project is cloned to
then you may do either:

    jruby -S rake rails:test:sqlite
    jruby -S rake rails:test:postgres
    jruby -S rake rails:test:mysql

If you have your rails source in another directory then you can pass
in **RAILS**:

    jruby -S rake rails:test:sqlite RAILS=../../somewhere/rails

If you are working on a more exotic adapter you can also pass in **DRIVER**:

    jruby -S rake rails:test:all DRIVER=derby

Note, that if you want to test against particular version of Rails you need
to check out the proper branch in Rails source (e.g. v5.0.2).  If you are
just starting to debug an adapter then running:

    jruby -S rake rails:test:sqlite:basic_test
    jruby -S rake rails:test:postgres:basic_test
    jruby -S rake rails:test:mysql:basic_test

is helpful since basic_test in active-record hits that 80% sweet spot.

[![Build Status][0]](http://travis-ci.org/#!/jruby/activerecord-jdbc-adapter)

Happy Testing!

[0]: https://secure.travis-ci.org/jruby/activerecord-jdbc-adapter.png
