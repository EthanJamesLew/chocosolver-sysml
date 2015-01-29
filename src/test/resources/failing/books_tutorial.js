scope({c0_Author:2, c0_Person:2, c0_authors:2, c0_books:2, c0_dob:2, c0_name:2, c0_page:2});
defaultScope(1);
intRange(-8, 7);
stringLength(16);

c0_Book = Abstract("c0_Book");
c0_Person = Abstract("c0_Person");
c0_Author = Abstract("c0_Author").extending(c0_Person);
c0_title = c0_Book.addChild("c0_title").withCard(1, 1);
c0_subtitle = c0_title.addChild("c0_subtitle").withCard(0, 1);
c0_year = c0_Book.addChild("c0_year").withCard(1, 1);
c0_page = c0_Book.addChild("c0_page").withCard(2);
c0_format = c0_Book.addChild("c0_format").withCard(1, 1).withGroupCard(1, 1);
c0_paper = c0_format.addChild("c0_paper").withCard(0, 1);
c0_hardcover = c0_paper.addChild("c0_hardcover").withCard(0, 1);
c0_electronic = c0_format.addChild("c0_electronic").withCard(0, 1);
c0_kind = c0_Book.addChild("c0_kind").withCard(1, 1).withGroupCard(1, 1);
c0_textbook = c0_kind.addChild("c0_textbook").withCard(0, 1);
c0_manual = c0_kind.addChild("c0_manual").withCard(0, 1);
c0_reference = c0_kind.addChild("c0_reference").withCard(0, 1);
c0_fiction = c0_kind.addChild("c0_fiction").withCard(0, 1);
c0_nonfiction = c0_kind.addChild("c0_nonfiction").withCard(0, 1);
c0_other = c0_kind.addChild("c0_other").withCard(0, 1);
c0_authors = c0_Book.addChild("c0_authors").withCard(1);
c0_ISBN = c0_Book.addChild("c0_ISBN").withCard(0, 1);
c0_GS1 = c0_ISBN.addChild("c0_GS1").withCard(0, 1);
c0_name = c0_Person.addChild("c0_name").withCard(1, 1);
c0_dob = c0_Person.addChild("c0_dob").withCard(0, 1);
c0_books = c0_Author.addChild("c0_books").withCard(1);
c0_GenerativeProgramming = Clafer("c0_GenerativeProgramming").withCard(1, 1).extending(c0_Book);
c0_Czarnecki = Clafer("c0_Czarnecki").withCard(1, 1).extending(c0_Author);
c0_Eisenecker = Clafer("c0_Eisenecker").withCard(1, 1).extending(c0_Author);
c0_title.refTo(string);
c0_subtitle.refTo(string);
c0_year.refTo(Int);
c0_other.refTo(string);
c0_authors.refToUnique(c0_Author);
c0_ISBN.refTo(string);
c0_GS1.refTo(string);
c0_name.refTo(string);
c0_dob.refTo(string);
c0_books.refToUnique(c0_Book);
c0_Book.addConstraint(implies(greaterThanEqual(joinRef(join($this(), c0_year)), constant(5)), some(join($this(), c0_ISBN))));
c0_ISBN.addConstraint(implies(greaterThanEqual(joinRef(join(joinParent($this()), c0_year)), constant(6)), some(join($this(), c0_GS1))));
c0_GenerativeProgramming.addConstraint(and(and(and(and(and(and(and(and(equal(joinRef(join($this(), c0_title)), constant("\"name\"")), none(join(join($this(), c0_title), c0_subtitle))), equal(joinRef(join($this(), c0_year)), constant(5))), equal(card(join($this(), c0_page)), constant(4))), some(join(join($this(), c0_format), c0_paper))), some(join(join($this(), c0_kind), c0_nonfiction))), equal(joinRef(join($this(), c0_authors)), union(global(c0_Czarnecki), global(c0_Eisenecker)))), equal(joinRef(join($this(), c0_ISBN)), constant("\"name\""))), none(join(join($this(), c0_ISBN), c0_GS1))));
c0_Czarnecki.addConstraint(and(equal(joinRef(join($this(), c0_name)), constant("\"name\"")), $in(global(c0_GenerativeProgramming), joinRef(join($this(), c0_books)))));
c0_Eisenecker.addConstraint(and(equal(joinRef(join($this(), c0_name)), constant("\"name\"")), $in(global(c0_GenerativeProgramming), joinRef(join($this(), c0_books)))));
