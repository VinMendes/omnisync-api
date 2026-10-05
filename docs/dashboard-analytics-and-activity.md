# Dashboard analytics and activity metrics

## Endpoints

`GET /api/dashboard/{systemClientId}/analytics?range=7d|30d`

Returns `inventoryByDay` from `inventory_history`. The database records a new
product snapshot whenever stock, price, active status, or ownership changes.
The migration seeds one snapshot for every existing product. Dates before the
first persisted snapshot are intentionally omitted because their inventory
cannot be reproduced safely.

`sponsoredSalesByDay` is currently returned as an empty list. The current
Mercado Livre order and listing payloads do not contain an official Ads
attribution identifier. Regular marketplace sales are not treated as sponsored
sales. This series must remain empty until an official Mercado Livre Ads source
is integrated and persisted.

`GET /api/activity/{systemClientId}/summary?date=yyyy-MM-dd&marketplace=MERCADO_LIVRE`

Returns all 24 hours in `salesByHour`, including zero-filled hours. Only sales
whose current status is `CONFIRMED` are aggregated. `marketplace` is optional
and accepts `MERCADO_LIVRE`, `SHOPEE`, or `AMAZON`.

## Security and time

Both endpoints validate that the authenticated user belongs to the requested
tenant before executing queries. Dashboard analytics requires `PRODUCT_READ`
and `SALE_READ`; activity summary requires `SALE_READ`. Aggregations filter by
`system_client_id` in every source table.

Date boundaries use the same application clock and local date convention as
the existing dashboard summary. Persisted history timestamps use UTC, matching
the centralized audit log convention.

Dashboard recent events now query `audit_logs`, ordered by `created_at DESC,
id DESC`, so user, product, sale, listing, and integration events share the same
centralized source.

## Performance validation

Repository integration tests exercise hourly aggregation with 10,000 confirmed
sales and require the warmed query to complete in less than one second. The
existing dashboard performance test continues to require the summary queries
to complete in less than 500 ms.
