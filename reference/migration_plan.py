"""Deterministic dependency planner; never opens a database or executes SQL."""
import heapq
import re

_NAME = re.compile("[a-z][a-z0-9_]{0,63}", re.ASCII)


class MigrationPlanError(ValueError):
    pass


def plan_migrations(migrations, applied=()):
    """Return pending IDs in lexical tie-break topological order."""
    if type(migrations) not in (tuple, list):
        raise MigrationPlanError("migrations must be a list")
    if len(migrations) > 10000:
        raise MigrationPlanError("migration count exceeds 10000")
    nodes = {}
    for item in migrations:
        if type(item) is not dict or set(item) != {"id", "depends_on"}:
            raise MigrationPlanError("each migration needs only id and depends_on")
        name, deps = item["id"], item["depends_on"]
        if type(name) is not str or not _NAME.fullmatch(name) or name in nodes:
            raise MigrationPlanError("invalid or duplicate migration id")
        if type(deps) is not list or any(type(d) is not str or not _NAME.fullmatch(d) for d in deps):
            raise MigrationPlanError("invalid dependencies")
        if len(set(deps)) != len(deps):
            raise MigrationPlanError("duplicate dependency")
        nodes[name] = set(deps)
    if type(applied) not in (tuple, list, set, frozenset):
        raise MigrationPlanError("applied must be an iterable of IDs")
    if any(type(name) is not str for name in applied):
        raise MigrationPlanError("applied IDs must be strings")
    applied_set = set(applied)
    if len(applied_set) != len(applied) or not applied_set <= nodes.keys():
        raise MigrationPlanError("duplicate or unknown applied migration")
    for name, deps in nodes.items():
        if not deps <= nodes.keys():
            raise MigrationPlanError("unknown migration dependency")
        if name in applied_set and not deps <= applied_set:
            raise MigrationPlanError("applied migration has unapplied dependency")
    outgoing = {name: set() for name in nodes}
    indegree = {name: len(deps) for name, deps in nodes.items()}
    for name, deps in nodes.items():
        for dep in deps:
            outgoing[dep].add(name)
    ready = [name for name, degree in indegree.items() if degree == 0]
    heapq.heapify(ready)
    ordered = []
    while ready:
        name = heapq.heappop(ready)
        ordered.append(name)
        for nxt in outgoing[name]:
            indegree[nxt] -= 1
            if indegree[nxt] == 0:
                heapq.heappush(ready, nxt)
    if len(ordered) != len(nodes):
        raise MigrationPlanError("dependency cycle")
    return [name for name in ordered if name not in applied_set]
