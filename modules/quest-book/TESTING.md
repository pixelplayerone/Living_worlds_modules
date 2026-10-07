# Testing

Pure checks, no server needed (Java 8+):

    javac -d out scripts/QuestData.java scripts/QuestIndex.java scripts/QuestPages.java tests/QuestBookTest.java
    java -cp out modules.questbook.QuestBookTest

The module itself is compiled by the server's script compiler at Java 8 source level when it starts.
