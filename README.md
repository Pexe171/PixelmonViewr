# Pixelmon Tracker

Radar client-side para Minecraft 1.21.1 com Pixelmon 9.3.16 e NeoForge 21.1.200.

O mod mostra Pokemon e PokeLoot que ja foram enviados ao cliente, ou seja, apenas alvos em chunks carregados. Ele nao consulta o servidor e nao encontra conteudo fora da distancia de renderizacao.

## Controles

- `F4`: abrir o menu HUD completo
- `J`: ligar/desligar o Auto Trainer
- `K`: mostrar/ocultar rastros no chao
- `F8`: ligar/desligar o rastreador
- `F9`: mostrar/ocultar Pokemon
- `F10`: mostrar/ocultar PokeLoot
- `F7`: mostrar/ocultar marcadores 3D
- `F6`: mostrar/ocultar radar circular

As teclas podem ser alteradas em **Opcoes > Controles > Pixelmon Tracker**.

## Filtros de busca

- `/ptracker filtro charmander`: mostra e alerta somente Charmander
- `/ptracker filtro charmander,pikachu,eevee`: procura varios Pokemon
- `/ptracker lista`: mostra os filtros ativos
- `/ptracker limpar`: remove o filtro e volta a mostrar todos
- `/ptracker somente-loot`: oculta Pokemon e deixa somente PokeLoot
- `/ptracker limpar-pegos`: esquece o historico de PokeLoot coletado

Quando um Pokemon filtrado surge nos chunks carregados, o mod toca um alerta e mostra a distancia. O cliente nao recebe dados de chunks distantes ou descarregados, portanto nenhum mod apenas de cliente consegue pesquisar literalmente o mapa inteiro.

## Informacoes exibidas

- Nome e nivel de cada Pokemon
- Indicacao de boss ou lendario
- Distancia, direcao relativa e coordenadas
- Tipo do PokeLoot
- Marcadores flutuantes visiveis atraves do terreno
- Radar circular rotativo com alcance de 128 blocos
- PokéLoot ja coletado e removido automaticamente da lista
- Rastro ciano ate o Pokemon mais proximo e laranja ate o PokeLoot disponivel mais proximo

## Compilar

Requer JDK 21.

```powershell
.\gradlew.bat build
```

O JAR final e criado em `build/libs/`.

## Instalar

Coloque o JAR do Pixelmon Tracker na pasta `mods` da mesma instancia que contem Pixelmon 9.3.16 e NeoForge 21.1.200. O mod e somente de cliente e nao precisa ser instalado no servidor.

Use apenas em mundo proprio, servidor privado autorizado ou ambiente em que esse tipo de mod seja permitido.

Ao clicar em uma PokeLoot, ela e registrada e removida imediatamente da lista. O historico
fica salvo por servidor, dimensao e coordenada, inclusive depois de reiniciar o jogo.

No menu `F4`, use **Somente PokeLoot** para ativar o preset. O botao
**Limpar pegos (N)** mostra quantos registros existem e permite restaurar todos a busca.

## Filtros de categoria

No menu `F4`, clique nos seletores para alternar entre:

- Pokemon: todos, normal, boss, Mega, lendario ou especiais
- Tier de boss: Common, Uncommon, Rare, Epic, Legendary, Ultimate, Haunted, Drowned ou Equal
- PokeLoot: Poke Ball, Ultra Ball, Master Ball, Beast Ball, Special ou Grotto

Os filtros de nome e categoria sao combinaveis. Por exemplo, `Charizard` + `Mega`,
ou categoria `Boss` + tier `Epic`.

## Caca inteligente

- Filtro opcional para mostrar somente Shiny
- Nivel minimo e maximo de 1 a 100; campo vazio remove o limite correspondente
- Prioridade: Shiny, Mega, lendario, boss, bonus do tier, nivel e distancia
- Alerta sonoro exclusivo quando um Shiny aparece nos chunks carregados
- Pagina **Alvos** no menu `F4` para fixar um resultado
- O alvo fixado mantem a ultima coordenada quando descarrega e volta ao vivo se reaparecer
- Enquanto um alvo esta fixado, o rastro segue somente ele

## Auto Trainer

O Auto Trainer fica desligado por padrao. Ao ligar com `J` ou pelo menu `F4`, ele:

- Procura Pokemon selvagem em ate 64 blocos e caminha ate o alvo
- Evita automaticamente Shiny, Mega, lendario e boss
- Evita alvos mais de cinco niveis acima do Pokemon mais forte da equipe
- Inicia a batalha, escolhe golpes por poder, precisao, STAB e efetividade de tipo
- Usa Mega Evolucao ou Dynamax quando disponivel e troca Pokemon desmaiado
- Faz troca estrategica com vida baixa ou quando outro Pokemon tem vantagem ofensiva muito maior
- A aba **Trainer** permite escolher o Pokemon fraco que deve receber experiencia
- No treino por troca, o escolhido inicia a batalha e troca no primeiro turno para o melhor reforco saudavel
- A escolha do reforco considera nivel, vida, golpes e vantagem de tipo, sem voltar ao Pokemon em treinamento enquanto houver outra opcao
- Para quando a equipe nao possui Pokemon apto ou quando outro menu esta aberto

O movimento possui salto e tentativa lateral simples para obstaculos, mas nao substitui um
sistema completo de pathfinding; supervisione o personagem perto de penhascos ou areas perigosas.
O botao **Proteger raros** vem ligado por padrao e pode ser desligado no menu `F4`.
As decisoes de alvo, golpe, troca e qualquer erro ficam registradas em `logs/latest.log`
com o prefixo `Auto Trainer`.
