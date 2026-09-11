# Auditoria centralizada

## Contrato

`GET /api/audit-logs/{systemClientId}`, autenticado pelo cookie JWT existente.
Pode consultar quem possui `ROLE_ADMIN` **ou** `PERM_AUDIT_READ`, somente na própria empresa.
Essa exceção de ADMIN é exclusiva da consulta de auditoria e segue o card; não libera
outras operações. Novos ADMIN incluem AUDIT_READ no padrão; a migration não altera
as permissões personalizadas existentes. Outros papéis precisam de concessão explícita
pela gestão de usuários já existente.

Filtros opcionais, combinados com AND:

- `userId`: ID positivo do autor.
- `role`: ADMIN, MANAGER, SELLER, VIEWER ou SYSTEM (papel no momento do evento).
- `action`: CREATE, UPDATE, ACTIVATE, DEACTIVATE, DELETE, CONNECT, DISCONNECT, SYNC, PUBLISH, CLOSE.
- `entityType`: USER, PRODUCT, SALE, INTEGRATION, LISTING.
- `from` e `to`: datas ISO-8601 sem offset, interpretadas em **UTC**; limites inclusivos.
- `offset`: quantidade exata de registros a pular, mínimo 0, máximo 2147483647.
- `limit`: 1 a 100; padrão 20.

Exemplo: `/api/audit-logs/1?action=UPDATE&entityType=PRODUCT&offset=0&limit=20`.
Resposta: `content`, `offset`, `limit`, `total_elements`, `has_next`. Cada evento
expõe os campos snake_case do card e o autor dentro de `user`. Ordenação por
`created_at DESC, id DESC`, inclusive quando eventos têm a mesma data.

Erros: 401 sem login; 404 para outra empresa (antes da consulta); 403 para não-ADMIN
sem AUDIT_READ; 400 para filtros/paginação/período inválidos. Não há API para criar,
editar ou apagar registros de auditoria.

## Persistência e transações

V13 cria `audit_logs` com JSONB para previous_data, new_data e metadata. Os índices
começam por system_client_id e atendem ordenação, autor, papel, ação e tipo de entidade.
Tenant é obrigatório e possui FK sem exclusão em cascata. A identidade do autor é um
snapshot, sem FK para users: excluir ou renomear o usuário não reescreve seu histórico.
created_at é armazenado em UTC.

`AuditService` exige uma transação existente (MANDATORY). O evento é inserido no
mesmo banco e transação da alteração. Não usa REQUIRES_NEW, fila ou operação assíncrona:
rollback do negócio remove os eventos; falha de persistência da auditoria também
reverte o negócio. A entidade JPA é imutável; essa propriedade não é uma proteção
contra administradores com acesso SQL direto.

Eventos de sucesso não representam tentativas. Falhas de chamada externa e sincronização
em andamento não criam SYNC de sucesso. A invalidação de uma integração por credencial
expirada/inválida pode produzir DISCONNECT mesmo que a tentativa de sync retorne erro:
nesse caso a desativação realmente foi commitada pelo comportamento existente.

Uma transação SQL não consegue desfazer operações já executadas no Mercado Livre.
Um sucesso remoto seguido de rollback local não gera evento de sucesso local; conciliação
de efeitos remotos/outbox é uma evolução separada, não uma garantia desta implementação.

## Eventos e autoria

| Fluxo | Eventos |
| --- | --- |
| Cadastro inicial e criação de membro | USER / CREATE |
| Alteração de usuário ou permissões | USER / UPDATE, com snapshots |
| Ativação/desativação efetiva | USER / ACTIVATE ou DEACTIVATE |
| Redefinição de senha | USER / UPDATE; somente indicação de mudança de credencial, nunca valores |
| Produtos | PRODUCT / CREATE, UPDATE, DELETE (exclusão lógica) |
| Venda manual ou por webhook | SALE / CREATE; alterações efetivas recebidas por webhook também geram UPDATE |
| Conexão e desconexão ML | INTEGRATION / CONNECT ou DISCONNECT |
| Sync bem-sucedido | INTEGRATION / SYNC, com instante anterior/novo e contagens |
| Mudanças em produtos durante sync | PRODUCT / CREATE, UPDATE ou DELETE, na mesma transação |
| Publicação e encerramento ML | LISTING / PUBLISH ou CLOSE, entity_id igual ao ID remoto |

Requisições comuns capturam a identidade do principal. Cadastro público captura o
primeiro administrador da empresa recém-criada, mesmo que a requisição contenha
cookie de outra empresa. Webhooks e invalidação automática de credenciais usam
user_id/user_email nulos, user_name=Sistema e user_role=SYSTEM: não inventam um autor humano.

O state OAuth novo inclui somente o ID do iniciador, protegido pela assinatura.
O callback valida a assinatura, a empresa, a atividade e a permissão atual desse
usuário antes da troca de código. O state e os códigos nunca entram na auditoria.
States antigos, ainda dentro da validade, continuam aceitos, mas usam SYSTEM porque
o formato antigo não continha autor. O cadastro e os callbacks são transacionais.

## Segurança dos snapshots

`AuditSnapshots` seleciona explicitamente campos de negócio: nome/e-mail/status/role/
permissões do usuário; SKU/nome/descrição/estoque/preço/status do produto; dados
operacionais da venda; marketplace/status/último sync da integração. O JSONB
`resource` arbitrário não é copiado: a alteração continua registrada, mas seus valores
livres não são reproduzidos. Isso evita registrar atributos que contenham credenciais
ou dados pessoais desnecessários, como CPF e conteúdo bruto de pedidos.

Além disso, `AuditSanitizer` remove recursivamente chaves de credenciais e faz uma
cópia defensiva de mapas/listas. Não serializa entidades, requests, responses, headers,
cookies ou objetos arbitrários. Metadata contém somente informações selecionadas pelo
servidor, nunca o corpo da requisição, o state OAuth ou respostas brutas do provedor.

## Compatibilidade e uso

O frontend não foi alterado. Todas as sincronizações atuais continuam sendo auditadas
com source=WEB. Para distinguir o gatilho, o mesmo endpoint de sync aceita o parâmetro
opcional `source=MANUAL` ou `source=AUTOMATIC`; esse rótulo é informativo, não concede
permissões. O backend não consegue distinguir um clique de um efeito automático da
tela quando os dois enviam requisições idênticas. Não foi criado um scheduler novo.

Foi adicionada `DELETE /api/integrations/mercadolivre/disconnect?systemClientId=1`,
com INTEGRATION_MANAGE e isolamento de empresa. Retorna 204, desativa a integração,
remove as credenciais locais e atualiza o indicador da empresa. Repetição não cria
evento duplicado. A operação é **desconexão local**: não revoga a autorização no site
do Mercado Livre nem encerra anúncios já publicados.

## Validação e implantação

Validação em 11/09/2026: **305 testes aprovados**, zero falhas/erros/skips.

`./gradlew test` usa PostgreSQL descartável local; nenhum dado da VM é necessário.
Os testes de auditoria exercitam MockMvc/JWT e serviços reais, simulando apenas o
cliente HTTP do Mercado Livre (e uma falha intencional do repositório de auditoria).
Cobrem filtros, roles/permissões, isolamento, paginação, rollback, falha de auditoria,
eventos dos serviços, callback sem cookie, revogação, sync e redelivery do webhook,
assim como ausência das credenciais sentinela nos registros.

A V13 será aplicada pelo Flyway ao iniciar a versão nova. Não há backfill de eventos
anteriores: o histórico começa nas operações realizadas depois da implantação.
Este card não executa deploy nem aplica a migration no banco compartilhado da VM.
