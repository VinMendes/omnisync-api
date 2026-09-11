# Autenticação e contrato de permissões

O backend autentica com Spring Security, carrega as permissões relacionais de cada
usuário e aplica autorização por operação. Produtos, vendas, empresas e integrações
validam a empresa do principal antes de acessar os serviços. A gestão de usuários
também limita consultas e alterações à empresa autenticada.

## Autenticação

- O bean `authenticationManager` em `SecurityBeansConfig` instancia um `ProviderManager`.
- Ele usa o `DaoAuthenticationProvider` do Spring, com BCrypt e `CustomUserDetailsService`.
- `OmniUserPrincipal` contém usuário, empresa, status e authorities, sem expor a entidade JPA.
- O filtro JWT e o refresh validam assinatura/expiração/tipo e recarregam a conta do banco.
- Usuário ou empresa desativados deixam de autenticar, inclusive com tokens já emitidos.
- O hash de senha é apagado do principal depois da autenticação.
- Credenciais inválidas retornam `401`; indisponibilidade interna do provider no login retorna `503`.

O filtro JWT próprio permanece. O `sub` continua sendo o e-mail, preservando tokens
existentes e consumidores de `Authentication.getName()`. Não há roles no JWT:
alterações em `roles.resource.permissions` são lidas na próxima requisição, sem reemitir o token.

Login e cadastro público de empresa emitem a resposta de autenticação e cookies HttpOnly.
Criar um funcionário retorna `UserResponse` sem emitir cookies ou trocar a sessão do administrador.
O access cookie continua com 15 minutos e o refresh cookie com sete dias. A validade
do JWT continua configurável (20 minutos para access na configuração atual); alinhar
os prazos dos cookies a essa configuração é uma melhoria separada.

## Fonte de verdade

As três tabelas existentes representam o acesso individual:

- `users`: características do usuário; `UserResource` preserva apenas seus metadados.
- `roles`: papel em `name` e permissões tipadas em `RoleResource`, no JSONB `resource`.
- `user_roles`: vínculo exclusivo entre usuário e sua role, dentro da mesma empresa.

Dois usuários `SELLER` da mesma empresa podem possuir configurações diferentes.
Alterar a seleção de um atualiza sua linha em `roles`, mantendo o vínculo e os demais usuários.
Exemplo de `roles.resource`:

```json
{
  "permissions": ["PRODUCT_READ", "SALE_READ"]
}
```

O catálogo é fechado nos enums `Role` e `Permission`. O mapa imutável
`Role.ROLE_DEFAULT_PERMISSIONS` define os padrões iniciais, não permissões obrigatórias:
uma lista explícita pode personalizar o conjunto. Criar membros e alterar usuários
exige `USER_MANAGE`, sempre dentro da empresa autenticada.

## Matriz inicial

| Permission | ADMIN | MANAGER | SELLER | VIEWER |
| --- | --- | --- | --- | --- |
| `PRODUCT_READ` | Sim | Sim | Sim | Sim |
| `PRODUCT_WRITE` | Sim | Sim | — | — |
| `LISTING_PUBLISH` | Sim | Sim | Sim | — |
| `SALE_READ` | Sim | Sim | Sim | Sim |
| `SALE_WRITE` | Sim | Sim | Sim | — |
| `USER_MANAGE` | Sim | — | — | — |
| `INTEGRATION_MANAGE` | Sim | — | — | — |
| `SETTINGS_MANAGE` | Sim | — | — | — |
| `AUDIT_READ` | Sim | — | — | — |

As authorities são `ROLE_ADMIN`/`ROLE_MANAGER`/`ROLE_SELLER`/`ROLE_VIEWER` mais
`PERM_<CÓDIGO>`, por exemplo `PERM_PRODUCT_WRITE`. Os endpoints usam as permissões
persistidas, sem um bypass geral para o nome `ADMIN`. A consulta de auditoria é a
exceção definida pelo seu card: aceita `ADMIN` ou `AUDIT_READ`, sempre na mesma empresa.
Roles existentes não recebem permissões novas automaticamente.

## Rotas e autorização

| Operação | Permissão |
| --- | --- |
| Ler produtos e categorias do catálogo ML | `PRODUCT_READ` |
| Criar, editar ou excluir produto | `PRODUCT_WRITE` |
| Publicar produto existente | `LISTING_PUBLISH` |
| Criar produto com `announcement: true` | `PRODUCT_WRITE` e `LISTING_PUBLISH` |
| Ler vendas | `SALE_READ` |
| Criar venda | `SALE_WRITE` |
| Criar membro, editar usuário ou alterar status | `USER_MANAGE` |
| Conectar ML, trocar código OAuth, atualizar marketplaces da empresa | `INTEGRATION_MANAGE` |
| Sincronizar catálogo ML | `INTEGRATION_MANAGE` e `PRODUCT_WRITE` |
| Editar ou desativar a empresa | `SETTINGS_MANAGE` |

`TenantAccess` confere os IDs da URL/query contra `OmniUserPrincipal.systemClientId`.
Esse controle é aplicado nos controllers autenticados; callbacks e webhooks mantêm
seus fluxos internos próprios. Um ID de outra empresa retorna `404` sem revelar dados.
Ausência de autenticação retorna `401`; ausência de permissão retorna `403` com
`ErrorResponse`, por exemplo `{"status":403,"message":"Permissão insuficiente: PRODUCT_WRITE"}`.

## Cadastros

`POST /api/auth/register-company` é público e recebe empresa e primeiro usuário juntos:

```json
{
  "companyName": "Empresa de exemplo",
  "document": "11222333000181",
  "name": "Administrador",
  "email": "admin@example.invalid",
  "password": "senha-de-exemplo",
  "resource": {"cpf": "00000000000"}
}
```

`RegistrationService` cria uma empresa nova e seu primeiro `ADMIN` na mesma transação.
O servidor define a empresa, o papel e as permissões; o payload não escolhe um cliente
existente. Uma falha ao criar o usuário desfaz a criação da empresa. Sucesso retorna
`200` com `AuthResponse` e cookies de login.

`POST /api/users` cria membros com cookie do administrador e `USER_MANAGE`. Aceita
o `RegisterRequest` existente (`systemClientId`, `name`, `email`, `password`, `resource`,
`role`, `permissions`), valida o cliente do principal e retorna `201` com `UserResponse`.
Não retorna tokens nem `Set-Cookie`.

`POST /api/auth/register` foi bloqueado com `403`. `POST /api/client` foi removido:
o cadastro público não cria mais uma empresa separadamente. `GET /api/client/checkCNPJ/**`
continua público; os demais endpoints de empresa exigem autenticação e isolamento.

## Entrada e compatibilidade com o frontend

Criação de membros e atualização aceitam `role` e `permissions` no topo, ou dentro de `resource`
como no frontend atual. Se os dois locais forem informados, devem ser equivalentes;
valores conflitantes retornam `400`.

- Role desconhecida, como `superuser`, retorna `400` com mensagem clara.
- Permission desconhecida ou uma lista malformada em entrada nova retorna `400`.
- Sem permissions no cadastro, aplicam-se os padrões do papel; sem role, usa-se `VIEWER`.
- Uma lista vazia explícita representa nenhuma permission, sem cair nos padrões.
- Atualizar apenas metadados preserva role e permissions. Trocar a role sem informar
  permissions aplica os padrões do novo papel.
- Metadados fora de role/permissions são preservados, inclusive valores nulos.

Para evitar uma alteração imediata do frontend, nomes são aceitos sem diferença de
maiúsculas/minúsculas e **`editor` é um alias legado de `SELLER`**, não um quinto papel.
Essa é uma compatibilidade de nomenclatura; a matriz acima define o papel canônico.

Os rótulos legados conhecidos em `src/lib/userResource.ts` são convertidos para códigos:
por exemplo, `Gestão de estoque` equivale a `PRODUCT_READ` + `PRODUCT_WRITE`,
`Anúncios` a `PRODUCT_READ` + `LISTING_PUBLISH`, `Vendas` a `SALE_READ` + `SALE_WRITE`,
e `Acesso total` ao catálogo inicial completo. Textos arbitrários não são aceitos.

`UserResponse`, inclusive em `GET /api/users/me`, expõe `role` e `permissions` canônicos
no topo, mantendo `resource` como projeção de compatibilidade (papel minúsculo e
rótulos antigos quando representam exatamente o mesmo conjunto). Um subconjunto
que não tenha rótulo equivalente mantém o código canônico, para não ampliar o acesso
quando a interface enviar os dados de volta.

O frontend preserva listas vazias e não restaura permissões revogadas na tela.
A lista canônica no topo permanece a referência exata. O helper HTTP renova o token
apenas em `401`; respostas `403` são apresentadas sem repetição automática da operação.

## Dados legados e migração

O histórico do Flyway inclui a normalização inicial da V7, a transferência para
`roles`/`user_roles` na V8, as configurações exclusivas por usuário na V9 e a reconciliação
legada na V10. A correção de autorização/cadastro não cria nem modifica migrations.

A leitura de `RoleResource` descarta permissões desconhecidas/malformadas, sem conceder
authorities arbitrárias. `users.resource` não concede acesso. As entradas novas de
permissões continuam sendo validadas estritamente. Uma conta sem vínculo válido com
role não autentica. A API não repõe permissões padrão a cada login ou startup.

## Testes

```sh
./gradlew test
```

Os testes usam valores sintéticos e, para integração, PostgreSQL descartável local
via [Embedded Postgres](https://github.com/zonkyio/embedded-postgres). Não requerem
Docker, não usam `DB_URL` da VM e não enviam e-mails nem chamam Mercado Livre.
O banco temporário é encerrado ao final. Java 25 continua sendo o requisito do projeto.

A cobertura inclui provider real/BCrypt, cookies/JWT/refresh, desativação de contas,
serialização com Jackson 2 e 3, persistência JSONB real, validação HTTP, matriz padrão,
backfill desde V6, consulta de verificação e preservação das atribuições em uma segunda
execução do Flyway. Os subconjuntos possíveis do catálogo são testados no round-trip legado.

## Publicação e limites

O frontend correspondente está na branch `fix/permission-access-flows`. Publicar backend
e frontend na mesma janela: a versão antiga do front chama o cadastro legado bloqueado,
enquanto a nova depende de `/api/auth/register-company` e `POST /api/users`. Login,
refresh e os contratos de leitura existentes permanecem compatíveis.

CSRF/cookies de produção, rotação/revogação de refresh tokens, convites por e-mail e
uma eventual adoção de Resource Server permanecem tarefas separadas. Esta entrega
não executa deploy nem acessa o banco compartilhado da VM.
