# Validação do resumo do dashboard

## Contrato e regras

- `GET /api/dashboard/{systemClientId}/summary?range=7d|30d` exige JWT.
- O tenant da URL deve ser o mesmo do principal autenticado e são exigidas as
  authorities `PRODUCT_READ` e `SALE_READ`.
- Apenas vendas cujo estado atual é `CONFIRMED` entram no faturamento. Quando há
  histórico, a primeira transição de `SaleLog` para `CONFIRMED` define o dia da venda.
- Os ranges incluem hoje: `7d` retorna hoje e os seis dias anteriores; `30d` retorna
  hoje e os 29 dias anteriores.
- A variação de faturamento de hoje compara hoje com ontem. Produtos e anúncios
  comparam o total ativo atual com os itens atuais que já existiam no início do range.
  A base anterior do estoque aplica ao estoque atual os deltas líquidos registrados
  nos logs de venda; vendas manuais antigas sem delta usam a quantidade confirmada.
- Qualquer base anterior igual a zero produz variação `0.0`.

## Validação automatizada equivalente à conferência manual

`DashboardMetricsRepositoryIntegrationTest` cria dois tenants no PostgreSQL descartável,
insere produtos, vendas confirmadas, pendentes/confirmadas posteriormente e canceladas,
e compara o resultado agregado com os valores conhecidos. Uma venda de R$ 50.000 e um
estoque de 9.999 do segundo tenant garantem que um vazamento seja imediatamente visível.

O mesmo teste insere 10.000 vendas com `generate_series`, aquece a consulta e exige que
as três queries agregadas terminem em menos de 500 ms. O repositório também registra em
INFO uma linha no formato:

```text
Dashboard summary queries completed in N ms for systemClientId=... rangeStart=... rangeEnd=...
```

Execute:

```sh
./gradlew test --tests '*Dashboard*'
```
