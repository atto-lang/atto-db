# Actor PostgreSQL pool contract

Actors share host-issued capabilities, never TCP sockets. Each physical connection has an exclusive protocol owner. The pool bounds admitted and executing work per tenant and in aggregate, and uses parameterized SQL. A transaction-local identity is not a substitute for PostgreSQL RLS and role restrictions.

This is a host-level contract; guest execution and native drivers require separate proof.
