# NomeDoProjeto

**Disciplina**: FGA0053 - Sistemas Multiagentes <br>
**Nro do Grupo (de acordo com a Planilha de Divisão dos Grupos)**: 01<br>
**Frente de Pesquisa**: Logística e cadeia de suprimentos<br>

## Alunos
| Matrícula  | Aluno                        |
| ---------- | ---------------------------- |
| 22/1007869 | Artur Henrique Holz Bartz    |
| 22/1022248 | Carlos Eduardo Mota Alves    |
| 19/0055201 | Matheus Calixto Vaz Pinheiro |
| 19/0115548 | Pedro Lucas Garcia           |
| 22/1008516 | Vitor Féijo Leonardo         |

## Sobre 
Nosso projeto é a implementação melroada em alugn pontos do artifo ()[], que não contém a implementação então nos propomos a implementar, mas basicamente é um sitam multi agente para determiarn o melhor fornecedor dado um conjto de fornecedores, ele utilza parametrso como precço, qualidade ... e vê a sinergia dos produtos coisa que outros artigos não tem e determina um fornecedor vencedor par um conjunto de produtos.

## Screenshots
Adicione 2 ou mais screenshots do projeto em termos de interface e/ou funcionamento.

## Instalação 
- **Linguagem:** Java 11 e Javascript
- **Tecnologias:** Maven,JADE e React.

Primeiro instale as dependências do projeto:

```bash
mvn install:install-file \
  -Dfile=lib/jade.jar \
  -DgroupId=com.tilab.jade \
  -DartifactId=jade \
  -Dversion=4.5.0 \
  -Dpackaging=jar
```

Em outro terminal:

```bash
cd mas-config-frontend
npm install
```

Depois, para construir o projeto, utilize o comando:

```bash
mvn clean package
```

```bash
npm run dev
```

Então , para executar o projeto, utilize o comando:

```bash
java -jar target/agentes-negociacao-1.0.0.jar
```

## Uso 
O uso é simples defini seus parametros e aguarde o vencedor.

## Vídeo
(Vídeo da apresentação)[https://youtu.be/SEqmFE10yeE]


## Participações
Apresente, brevemente, como cada membro do grupo contribuiu para o projeto.
|Nome do Membro | Contribuição | Significância da Contribuição para o Projeto (Excelente/Boa/Regular/Ruim/Nula) | Comprobatórios (ex. links para commits)
| -- | -- | -- | -- |
| Fulano  |  Programação dos Fatos da Base de Conhecimento Lógica | Boa | Commit tal (com link)

## Outros 
Quaisquer outras informações sobre o projeto podem ser descritas aqui. Não esqueça, entretanto, de informar sobre:
(i) Lições Aprendidas;
(ii) Percepções;
(iii) Contribuições e Fragilidades, e
(iV) Trabalhos Futuros.

## Fontes
Referencie, adequadamente, as referências utilizadas.
Indique ainda sobre fontes de leitura complementares.
