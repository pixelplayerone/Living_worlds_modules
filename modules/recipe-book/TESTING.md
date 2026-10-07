# Testing the Recipe Book

## Automated (no server needed)

```
javac -d out scripts/RecipeIndex.java scripts/RecipePages.java tests/RecipeBookTest.java
java -cp out modules.recipebook.RecipeBookTest
```

Expect `71 passed, 0 failed`. It covers the source filter (a 100% recipe with no source is excluded, one with a
drop is kept), search, totals, paging, escaping of names and search text, and page size.

## In game

1. Install, set `Enabled = True`, restart. The log should show `Recipe Book enabled` and `3 registration(s)`.
2. Alt+B. A **Recipe Book** button sits at the bottom of the left column.
3. Open it. Counts appear in the category/grade grid. First open prints
   `RecipeBook: N recipes with a drop or quest source; M left out`.
4. Weapons, C: a list of recipes. Click one: the ingredient have/need numbers should match your inventory plus
   warehouse (put 5 of something in the warehouse and check it counts).
5. Click an ingredient: monsters with level and chance. Click a monster name: the minimap opens with a marker. Click another monster: the first marker is replaced. Type `.clearmark` (or press Clear map marker on the front page): the marker disappears.
6. Search `mithril`: recipes using it appear. Search `<b>`: no markup should render.
7. Add two recipes to goals, open My Goals: shopping list totals, "Recipes to find" for scrolls you do not own.
   Log out and back in: the goals are still there.
8. Learn a recipe from a scroll: its entries get a green star and "learned".
9. `.recipebook` opens the book from chat.
10. Set `Enabled = False`, restart: the tab is gone and the other board tabs are unchanged.
