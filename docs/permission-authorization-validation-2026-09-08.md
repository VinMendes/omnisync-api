# Validação de autorização — 08/09/2026

Base: commit `d203c5f`, branch `FEAT/permission-based-authorization`.

O carregamento das permissões relacionais e os bloqueios dos endpoints mapeados funcionam. A validação adicional identificou quatro caminhos sem a proteção necessária; a aplicação ainda não deve ser considerada pronta do ponto de vista de controle de acesso.

## Execução e limites

```sh
./gradlew test
```

Resultado: **238 testes, 234 aprovados, 4 falhas, nenhuma ignorada**.

A nova classe `RelationalPermissionFlowIntegrationTest` acrescenta 27 casos: 23 aprovados e 4 regressões reproduzidas. Os 211 testes anteriores continuam aprovados. As falhas novas foram mantidas para orientar as correções, sem alterar o código de produção.

Os testes usam `@SpringBootTest`, MockMvc, login com senha/BCrypt, JWT em cookie HttpOnly, serviços de usuários/produtos/vendas e PostgreSQL embarcado com Flyway. As alterações de permissões são descarregadas no banco e o contexto JPA é limpo antes da consulta seguinte, evitando validar apenas objetos em memória. Somente `MercadoLivreListingService` é simulado na nova classe, para observar chamadas sem publicar ou sincronizar anúncios reais.

O banco da VM não foi acessado. A interface não foi exercitada em navegador: seu payload foi conferido no código e reproduzido na API, e as funções de apresentação de permissões e erros foram executadas diretamente com Node.

## Comportamentos confirmados

| Cenário | Resultado |
| --- | --- |
| ADMIN e MANAGER com permissões padrão | Leitura de produtos/vendas, criação de produto e venda: 200, com persistência real |
| SELLER com permissões padrão | Leituras e criação de venda: 200; criação de produto: 403 |
| VIEWER com permissões padrão | Leituras: 200; criação de produto/venda: 403, sem alteração de estoque |
| ADMIN com lista vazia de permissões | 403 nos 14 endpoints mapeados, com o código da permissão ausente |
| Dois SELLER da mesma empresa, ligados a roles distintas | Conceder escrita a um não altera o acesso do outro |
| PUT de permissões no formato do front (`role`, `permissions`) | Persiste a seleção na role exclusiva do usuário |
| PUT no formato legado (`resource.role`, `resource.permissions`) | Mesmo comportamento, com resposta compatível |
| Reutilização do cookie após concessão e revogação | A decisão muda na requisição seguinte, sem novo login |
| Lista explícita `permissions: []` | Backend preserva a lista vazia e bloqueia leitura/escrita |
| Edição de permissões | Mantém os IDs em `user_roles`; não cria linhas extras em `roles` |
| `users.resource` após edição | Não contém `role` nem `permissions`; esses campos vêm do relacionamento |
| GET `/api/users/me` | Expõe os códigos no topo e os rótulos compatíveis em `resource` |
| Usuário sem USER_MANAGE alterando status | 403, sem desativar o alvo |
| Administrador editando usuário de outra empresa | 404 |
| Desativação do usuário | O cookie anterior passa a receber 401 |
| Rotas públicas descritas no card | Continuam públicas na suíte existente |

O nome `ADMIN`, por si só, não concede todas as permissões: o acesso depende da lista efetivamente persistida. Os padrões são utilizados na criação/resolução das permissões, não para substituir a configuração a cada requisição.

## Falhas reproduzidas

### 1. Cadastro público concede acesso administrativo a uma empresa existente

Uma chamada sem autenticação a `POST /api/auth/register`, indicando a empresa já existente, `role: "admin"` e `permissions: ["Acesso total"]`, retornou **200** e gravou todas as permissões na nova role, incluindo USER_MANAGE e PRODUCT_WRITE.

Esperado no teste: 403 e nenhuma concessão administrativa. Origem: `AuthService.register` aceita a empresa e o acesso do payload; `/api/auth/**` é público.

Teste: `anonymousRegistrationMustNotGrantAdministrativeAccessToAnExistingCompany`.

Correção a definir: separar o cadastro público de uma nova empresa da criação de membros de uma empresa existente; esta última deve validar identidade, empresa e USER_MANAGE. O front atual cria membros com `credentials: 'omit'`, portanto essa mudança também exige ajustar esse fluxo ou adotar convite validado.

### 2. Trocar o systemClientId permite ler produtos de outra empresa

Um SELLER com PRODUCT_READ consultou `GET /api/products/{outraEmpresa}`. A resposta foi **200** e incluiu o produto exclusivo da outra empresa.

Esperado no teste: 404, coerente com o isolamento já aplicado em usuários, sem retornar dados de outra empresa. `ProductController.getAll` valida a permissão, mas o serviço confia no identificador da URL.

Teste: `productReadMustNotExposeAnotherCompanyByChangingThePath`.

Correção: vincular o cliente solicitado ao cliente do principal autenticado e revisar o mesmo padrão nos demais endpoints que recebem IDs de empresa.

### 3. PRODUCT_WRITE permite publicar pela criação de produto

Um usuário com somente PRODUCT_WRITE foi corretamente bloqueado em `POST /api/products/{client}/{id}/announce`, mas `POST /api/products/{client}` com `announcement: true` retornou **200** e chamou `MercadoLivreListingService.createListing`.

Esperado: 403 e nenhuma chamada de publicação. O caminho alternativo está em `ProductService.create`.

Teste: `productWriteAloneMustNotPublishThroughTheCreatePayload`.

Correção: exigir LISTING_PUBLISH adicionalmente sempre que a criação também solicitar publicação.

### 4. VIEWER consegue disparar sincronização do catálogo

Um VIEWER com somente PRODUCT_READ e SALE_READ chamou `POST /api/integrations/mercadolivre/catalog/{client}/sync`: recebeu **200**, e a operação chegou a `listAllClientListings`.

Esperado: 403 antes de executar a sincronização, operação capaz de criar, atualizar e desativar produtos. Esse método do `MercadoLivreCatalogController` não possui uma exigência de permissão.

Teste: `viewerMustNotMutateTheCatalogThroughMarketplaceSync`.

Correção: definir e aplicar a permissão da sincronização, considerando INTEGRATION_MANAGE e as alterações de produtos realizadas pela operação.

## Observação verificada no front

Executar `parseUserPermissions({role: 'editor', permissions: []}, 'editor')` em `src/lib/userResource.ts` retornou `['Anúncios', 'Gestão de estoque']`. A função trata a lista vazia como ausência de configuração. Isso faz a tela exibir permissões revogadas e pode reintroduzi-las caso alguém salve o formulário com esses valores.

O parser de erro reconheceu corretamente o corpo `{status: 403, message: 'Permissão insuficiente: PRODUCT_WRITE'}`. As correções de interface não foram implementadas nesta validação.

## Arquivos e reprodução focalizada

```sh
./gradlew test --tests com.puccampinas.omnisync.config.security.RelationalPermissionFlowIntegrationTest
```

- Testes novos: `src/test/java/com/puccampinas/omnisync/config/security/RelationalPermissionFlowIntegrationTest.java`.
- Relatório Gradle: `build/reports/tests/test/index.html`.
- Nenhum commit, push, PR, deploy ou alteração em migration foi feito durante esta validação.
