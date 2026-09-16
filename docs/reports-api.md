# OMNI-76 - Relatórios PDF

`POST /api/reports/{systemClientId}/generate`

Requer autenticação. O cliente da URL deve ser o cliente da sessão; um cliente
divergente ou inexistente retorna 404. SALES exige SALE_READ; INVENTORY e LISTINGS
exigem PRODUCT_READ. A autorização é aplicada no serviço antes das consultas.

```json
{
  "report_type": "SALES",
  "format": "PDF",
  "period": { "start": "2026-09-01", "end": "2026-09-15" },
  "marketplaces": ["MANUAL", "MERCADO_LIVRE"],
  "fields": ["created_at", "product_name", "sku", "quantity", "total_value", "channel", "status"]
}
```

## Campos permitidos (na ordem enviada)

| Tipo | Campos |
| --- | --- |
| SALES | id, created_at, product_name, sku, quantity, total_value, channel, status, external_reference_id |
| INVENTORY | id, sku, product_name, stock, reserved_stock, available_stock, minimum_stock, price, inventory_value |
| LISTINGS | product_name, sku, marketplace, external_id, price, stock, status |

`fields` deve conter pelo menos um campo, sem duplicatas. Campos de outro tipo
também são inválidos. `format` aceita somente PDF. Valores de tipo, formato e
marketplace são sensíveis à caixa e seguem os exemplos acima.

## Filtros e dados

- SALES usa `sales.created_at`, com início e fim inclusivos (consulta até o início
  do dia seguinte, exclusivo). Sem `period`, inclui todo o histórico. Se informado,
  o período deve ter início e fim ISO válidos e fim não anterior ao início.
- SALES inclui todos os status, sem excluir cancelados ou pendentes. Canais:
  MANUAL, PHYSICAL, MERCADO_LIVRE, SHOPEE e AMAZON. Filtro ausente/vazio inclui todos.
- INVENTORY é a posição atual dos produtos ativos. `available_stock = stock -
  reserved_stock`; `inventory_value = stock * price`. Não aceita período histórico
  nem filtro de marketplace, porque o estoque é compartilhado por produto.
- LISTINGS é o último snapshot local dos anúncios persistidos em
  `products.resource.{mercado_livre,shopee,amazon}`, com `item_id` preenchido.
  Inclui anúncios pausados/encerrados para informar sua situação. Não aceita período
  histórico. Marketplace ausente/vazio inclui todos os marketplaces suportados.
- Preço e quantidade do anúncio usam os valores persistidos da resposta do
  marketplace (`raw.price` e `raw.available_quantity`, ou os mesmos atributos na
  raiz do snapshot). Dados não disponíveis são exibidos como `-`, sem inventar
  valores a partir do estoque ou preço do produto. Não há chamada externa durante
  a geração. A data de geração não representa a data de sincronização do anúncio.
- Todas as consultas usam o cliente autenticado e o JOIN de venda/produto também
  verifica esse cliente.

## Resposta

200, `Content-Type: application/pdf`,
`Content-Disposition: attachment; filename="relatorio-vendas-YYYY-MM-DD.pdf"`,
`Cache-Control: no-store`. Corpo binário. O header Content-Disposition é exposto
por CORS para permitir o download no frontend.

O PDF informa empresa, tipo, período/posição atual, instante de geração e filtros.
Somente os campos selecionados aparecem na tabela. Cabeçalhos se repetem nas
novas páginas; células longas quebram em linhas/páginas. Relatórios sem resultados
continuam válidos e exibem a mensagem de ausência de registros.

Erros: 400 para parâmetros inválidos; 401 sem autenticação; 403 sem a permissão
necessária; 404 para cliente inexistente/divergente; 422 para falha na criação
do documento. Nenhuma migration é necessária.

## Verificação

`./gradlew test --tests 'com.puccampinas.omnisync.core.report.*'`

O teste do renderer escreve uma amostra determinística para QA visual em
`build/reports/report-preview.pdf` (ignorada pelo Git). A fonte padrão do PDF cobre
acentos portugueses; caracteres fora do repertório WinAnsi são substituídos por
`?` para não invalidar o documento.
