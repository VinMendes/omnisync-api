# Teste integrado de autenticação com o frontend

Execução na noite de 30 para 31/08/2026, na cópia de trabalho da branch
`refactor/auth-spring-security`. Este relatório registra testes reais pelo navegador,
não apenas os testes MockMvc/JUnit do backend.

## Ambiente

- Frontend existente `omnisync-web`, sem alterações de código, em `http://localhost:15173`.
- API real desta branch em `http://localhost:18080`, sem mocks de autenticação ou persistência.
- PostgreSQL local descartável via Embedded Postgres; Flyway executou V1–V7 nesse banco.
- Uma empresa fictícia e quatro contas com endereços `@example.invalid`.
- `VITE_SKIP_AUTH=false`; base da API e proxy de desenvolvimento apontados explicitamente para localhost.
- Cookies HttpOnly exclusivos do teste (`OMNI_E2E_ACCESS`/`OMNI_E2E_REFRESH`), sem Secure para HTTP local.
- Access JWT com validade de um minuto, somente nesta execução, para testar refresh automático.
- Interface exercitada com a habilidade Browser; a visualização foi ajustada para desktop
  porque a largura inicial deixava botões da tabela fora da área clicável.

O banco da VM não foi acessado ou migrado. Não foram acionados envio de e-mail,
autorização externa de marketplaces, publicação, push ou PR. As credenciais compartilhadas
na conversa não foram usadas. A alteração preexistente de `package-lock.json` foi preservada.

## Resultados observados

| Cenário | Resultado |
| --- | --- |
| Abrir a aplicação sem sessão | Tela de login; `/me` e refresh sem credenciais retornam `401`. |
| Login com senha incorreta | `401` e mensagem “Credenciais inválidas” na interface. |
| Login com conta ativa | `200`, carregamento de `/me` e identificação correta no menu. |
| Recarregar depois do login | Sessão preservada pelos cookies; nenhum login adicional necessário. |
| Access JWT expirado durante listagem/edição | A interface recebe `401`, faz refresh com `200` e repete GET/PUT com `200`. |
| Criar usuário pela gestão | Cadastro persistido; sessão do administrador não foi substituída. |
| Criar Editor com estoque e anúncios | Interface mostra Editor; API retorna `SELLER`, `PRODUCT_READ`, `PRODUCT_WRITE`, `LISTING_PUBLISH`. |
| Alterar papel e permissões | Edição persistida; interface mostra Gerente com estoque, anúncios e vendas. |
| Recarregar depois da edição | Papel e permissões continuam corretos após nova leitura da API. |
| Login da conta criada pelo frontend | Conta reconhecida e sessão carregada com nome/e-mail corretos. |
| Logout seguido de recarregamento | Retorno ao login; `/me` e refresh passam a retornar `401`. |
| Desativar conta pela gestão | PATCH de status retorna `200`; tabela mostra Inativo. |
| Login de conta inativa com senha correta | Negado com `401` e mensagem genérica de credenciais inválidas. |
| Salvar permissões vazias e editar novamente | **Falha de integração no frontend**, detalhada abaixo. |

Os eventos HTTP foram observados nos logs locais do servidor, sem registrar tokens
ou senhas. Consultas adicionais à API local confirmaram os campos canônicos devolvidos
para as contas fictícias.

## Falha confirmada: permissões vazias são restauradas pela interface

Reprodução na tela de gestão:

1. Abrir uma conta MANAGER e desmarcar todas as permissões.
2. Salvar. A API grava e retorna `permissions: []`, inclusive dentro de `resource`.
3. A tabela passa a mostrar as permissões padrão de Gerente, embora a API retorne `[]`.
4. Reabrir a edição, alterar apenas o status e salvar.
5. O frontend envia as permissões padrão de volta junto com o resource, antes de
   atualizar o status. A API então passa a retornar as cinco permissões padrão de MANAGER.

Portanto, não é somente uma diferença de apresentação: uma edição posterior pode
restaurar permissões que tinham sido removidas.

Causa no frontend: `src/lib/userResource.ts`, função `parseUserPermissions`, exige
`raw.length > 0` para aceitar a lista; uma lista vazia cai nos padrões do papel.
`src/screens/UsersScreen.tsx` usa essa projeção para montar o payload de atualização.

Ajuste necessário em uma entrega do frontend: distinguir lista ausente de lista
explicitamente vazia e preservar o conjunto canônico recebido da API. Até esse
ajuste, não considerar a gestão de contas sem permissões pronta para uso.

Nenhuma correção foi aplicada ao frontend neste teste, respeitando o escopo combinado.

## Outra observação de interface

No modal de criação, clicar no texto de uma permissão não alterna a seleção:
é necessário clicar no quadrado. O cadastro com permissões personalizadas funcionou
ao usar esse controle. No modal de edição, o clique no texto também funciona.
Esse comportamento já está no frontend; não foi alterado.

## Limites da validação

Este teste confirma os fluxos descritos em desenvolvimento local. Não valida cookies
em HTTPS/domínios de produção, configuração da VM ou integrações externas.
Também não implementa autorização por operação, controle de quem atribui permissões
ou isolamento entre empresas; essas etapas continuam pendentes, como descrito em
[Autenticação e contrato de permissões](authentication-and-user-permissions.md).

Os arquivos de preparação e logs desta execução foram gerados em um diretório temporário,
fora dos repositórios. Não há necessidade de copiar variáveis ou credenciais da VM
para reproduzir os testes com o banco descartável.

Ao final, frontend, backend e PostgreSQL de teste foram encerrados, com as portas locais
liberadas. O arquivo temporário de cookies do cliente HTTP foi removido e a visualização
original do navegador foi restaurada.
