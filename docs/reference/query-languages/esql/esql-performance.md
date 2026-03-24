---
applies_to:
  stack:
  serverless:
navigation_title: "Performance guidelines"
---

# {{esql}} performance guidelines [esql-performance-guidelines]

{{esql}} runs as a pipeline: each processing command sees the rows produced by the previous step. **How expensive a query is depends on your data volume, mappings, cluster load, and the {{esql}} version**—two queries that look similar can compile and execute differently.

::::{tip}
When tuning a hot path, measure before and after. The [{{esql}} query log](/reference/query-languages/esql/esql-query-log.md) helps you inspect how queries behaved historically on the cluster.
::::

## Predicates and expressions [esql-performance-predicates]

Sometimes you can express the same intent with different functions and operators. One form may avoid extra per-row work in common cases, but **relative cost can change as the engine evolves**, so treat the following as guidance, not a guarantee.

**Example: non-empty strings**

You might filter “has a non-empty string” using `length`:

```esql
FROM logs
| WHERE length(message) > 0
```

An alternative is to test for null and empty string explicitly:

```esql
FROM logs
| WHERE message IS NOT NULL AND message != ""
```

In many deployments the second pattern is cheaper because it can avoid computing `length` for every row. **Choose the form that matches your semantics**: multivalued fields, whitespace-only values, and SQL three-valued logic (`NULL` in conditions) can make these expressions behave differently. When in doubt, validate results on a sample of your data.

## Reduce work early [esql-performance-reduce-work]

- **Filter as early as you can.** Push selective [`WHERE`](/reference/query-languages/esql/commands/where.md) conditions (for example a tight time range on `@timestamp`) so fewer rows flow through later commands.
- **Cap row counts** with [`LIMIT`](/reference/query-languages/esql/commands/limit.md) when you only need a bounded result set.
- **Project only the columns you need** using [`KEEP`](/reference/query-languages/esql/commands/keep.md) (or narrow field lists at the source) so each step moves less data.
- **Metadata and `_source`.** Requesting [`_source`](/reference/query-languages/esql/esql-metadata-fields.md) or wide metadata can add cost, especially with [synthetic `_source`](/reference/elasticsearch/mapping-reference/mapping-source-field.md#synthetic-source). See [{{esql}} metadata fields](/reference/query-languages/esql/esql-metadata-fields.md) for trade-offs.

## Optimizer and command order [esql-performance-optimizer]

The {{esql}} optimizer may reorder independent steps so they execute more efficiently. For example, `WHERE` and `EVAL` are often planned the same regardless of pipe order when the logic allows it. For details and examples, see [Basic {{esql}} syntax](/reference/query-languages/esql/esql-syntax.md).

## Source commands and time series [esql-performance-source]

For time series data, the [`TS`](/reference/query-languages/esql/commands/ts.md) source command is tailored for time series indices and related aggregations; using [`FROM`](/reference/query-languages/esql/commands/from.md) alone may still work but is not optimized for that workload. See the [`TS` command reference](/reference/query-languages/esql/commands/ts.md) for when to prefer it.

## Joins [esql-performance-joins]

[`LOOKUP JOIN`](/reference/query-languages/esql/esql-lookup-join.md) cost grows with how many rows participate in the join. {{esql}} tries to apply `WHERE` filters before the join when possible—write selective filters and prefer smaller lookup sides when you can. See [Join data with `LOOKUP JOIN`](/reference/query-languages/esql/esql-lookup-join.md).

## Aggregations [esql-performance-aggregations]

[`STATS ... BY`](/reference/query-languages/esql/commands/stats-by.md) performance depends on how grouping is expressed. Grouping on a **single** grouping expression is typically better optimized than grouping on many expressions or complex keys. See the [`STATS`](/reference/query-languages/esql/commands/stats-by.md) command documentation for behavior and options.

## When you hit limits [esql-performance-limits]

- [{{esql}} limitations](/reference/query-languages/esql/limitations.md) describe product constraints that affect what you can run.
- [{{esql}} circuit breaker settings](/reference/query-languages/esql.md#esql-circuit-breaker) on this reference overview point to cluster settings that cap memory use for {{esql}}.
