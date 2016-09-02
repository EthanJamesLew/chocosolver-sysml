package org.clafer.ir.analysis;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.clafer.collection.DisjointSets;
import org.clafer.collection.Triple;
import org.clafer.common.UnsatisfiableException;
import org.clafer.common.Util;
import org.clafer.domain.BoolDomain;
import static org.clafer.domain.BoolDomain.FalseDomain;
import static org.clafer.domain.BoolDomain.TrueDomain;
import org.clafer.domain.Domain;
import org.clafer.domain.Domains;
import static org.clafer.domain.Domains.boundDomain;
import static org.clafer.domain.Domains.constantDomain;
import static org.clafer.domain.Domains.enumDomain;
import org.clafer.ir.IllegalIntException;
import org.clafer.ir.IllegalSetException;
import org.clafer.ir.IllegalStringException;
import org.clafer.ir.IrAdd;
import org.clafer.ir.IrAllDifferent;
import org.clafer.ir.IrArrayEquality;
import org.clafer.ir.IrArrayToSet;
import org.clafer.ir.IrBoolChannel;
import org.clafer.ir.IrBoolExpr;
import org.clafer.ir.IrBoolExprVisitorAdapter;
import org.clafer.ir.IrBoolVar;
import org.clafer.ir.IrCard;
import org.clafer.ir.IrCompare;
import org.clafer.ir.IrConcat;
import org.clafer.ir.IrConstant;
import org.clafer.ir.IrCount;
import org.clafer.ir.IrElement;
import org.clafer.ir.IrIfOnlyIf;
import org.clafer.ir.IrIntArrayExpr;
import org.clafer.ir.IrIntArrayVar;
import org.clafer.ir.IrIntChannel;
import org.clafer.ir.IrIntExpr;
import org.clafer.ir.IrIntVar;
import org.clafer.ir.IrJoinFunction;
import org.clafer.ir.IrJoinRelation;
import org.clafer.ir.IrLength;
import org.clafer.ir.IrMember;
import org.clafer.ir.IrMinus;
import org.clafer.ir.IrModule;
import org.clafer.ir.IrNot;
import org.clafer.ir.IrNotMember;
import org.clafer.ir.IrOffset;
import org.clafer.ir.IrPrefix;
import org.clafer.ir.IrRegister;
import org.clafer.ir.IrSelectN;
import org.clafer.ir.IrSetArrayExpr;
import org.clafer.ir.IrSetArrayVar;
import org.clafer.ir.IrSetElement;
import org.clafer.ir.IrSetEquality;
import org.clafer.ir.IrSetExpr;
import org.clafer.ir.IrSetUnion;
import org.clafer.ir.IrSetVar;
import org.clafer.ir.IrSingleton;
import org.clafer.ir.IrSortSets;
import org.clafer.ir.IrSortStrings;
import org.clafer.ir.IrSortStringsChannel;
import org.clafer.ir.IrStringCompare;
import org.clafer.ir.IrStringExpr;
import org.clafer.ir.IrStringVar;
import org.clafer.ir.IrSubarray;
import org.clafer.ir.IrSubsetEq;
import org.clafer.ir.IrSuffix;
import org.clafer.ir.IrUtil;
import org.clafer.ir.IrVar;
import org.clafer.ir.IrWithin;
import org.clafer.ir.Irs;
import static org.clafer.ir.Irs.False;
import static org.clafer.ir.Irs.True;
import static org.clafer.ir.Irs.Zero;
import static org.clafer.ir.Irs.add;
import static org.clafer.ir.Irs.domainInt;
import static org.clafer.ir.Irs.length;
import static org.clafer.ir.Irs.set;
import static org.clafer.ir.Irs.string;

/**
 * @author jimmy
 */
public class Coalescer {

    private Coalescer() {
    }

    private static String stripParens(String name) {
        if (name.startsWith("(") && name.endsWith(")")) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    private static String joinNames(List<String> names) {
        return names.stream().map(Coalescer::stripParens).collect(Collectors.joining(";", "(", ")"));
    }

    public static Triple<Map<IrIntVar, IrIntVar>, Map<IrSetVar, IrSetVar>, IrModule> coalesce(IrModule module) {
        Triple<DisjointSets<IrIntVar>, DisjointSets<IrSetVar>, DisjointSets<IrStringVar>> graphs
                = findEquivalences(module.getConstraints());
        DisjointSets<IrIntVar> intGraph = graphs.getFst();
        DisjointSets<IrSetVar> setGraph = graphs.getSnd();
        DisjointSets<IrStringVar> stringGraph = graphs.getThd();
        Map<IrIntVar, IrIntVar> coalescedInts = new HashMap<>();
        Map<IrSetVar, Triple<String, Domain, Domain>> coalescedSetNameEnvKers = new HashMap<>();
        Map<IrSetVar, IrSetVar> coalescedSets = new HashMap<>();
        Map<IrStringVar, IrStringVar> coalescedStrings = new HashMap<>();

        List<IrSetVar> setVars = new ArrayList<>();
        List<IrStringVar> stringVars = new ArrayList<>();

        for (IrVar var : module.getVariables()) {
            if (var instanceof IrConstant) {
                // Do nothing
            } else if (var instanceof IrSetVar) {
                IrSetVar set = (IrSetVar) var;
                Domain ker = set.getKer();
                Domain env = set.getEnv();
                IrIntVar card = set.getCardVar();
                assert card.getLowBound() >= ker.size() && card.getHighBound() <= env.size();
                setVars.add(set);
            } else if (var instanceof IrStringVar) {
                IrStringVar string = (IrStringVar) var;
                IrIntVar[] chars = string.getCharVars();
                IrIntVar length = string.getLengthVar();
                for (int i = 0; i < length.getDomain().getLowBound(); i++) {
                    if (chars[i].getDomain().contains(0)) {
                        Domain domain = chars[i].getDomain().remove(0);
                        failIf(domain.isEmpty());
                        intGraph.union(chars[i], tint(domain));
                    }
                }
                for (int i = length.getDomain().getHighBound(); i < chars.length; i++) {
                    intGraph.union(chars[i], Zero);
                }
                stringVars.add(string);
            }
        }

        for (Set<IrStringVar> component : stringGraph.connectedComponents()) {
            if (component.size() > 1) {
                Iterator<IrStringVar> iter = component.iterator();
                IrStringVar var = iter.next();
                IrIntVar length = var.getLengthVar();
                IrIntVar[] chars = var.getCharVars();
                int lengthLow = var.getLength().getLowBound();
                int lengthHigh = var.getLength().getHighBound();
                while (iter.hasNext()) {
                    var = iter.next();
                    intGraph.union(length, var.getLengthVar());
                    lengthLow = Math.max(lengthLow, var.getLength().getLowBound());
                    lengthHigh = Math.min(lengthHigh, var.getLength().getHighBound());
                    for (int i = 0; i < Math.min(chars.length, var.getCharVars().length); i++) {
                        intGraph.union(chars[i], var.getCharVars()[i]);
                    }
                    for (int i = chars.length; i < var.getCharVars().length; i++) {
                        intGraph.union(var.getCharVars()[i], Zero);
                    }
                }
            }
        }
        for (Set<IrSetVar> component : setGraph.connectedComponents()) {
            if (component.size() > 1) {
                Iterator<IrSetVar> iter = component.iterator();
                IrSetVar var = iter.next();
                IrIntVar card = var.getCardVar();
                List<String> names = new ArrayList<>(component.size());
                if (!(var instanceof TempSetVar)) {
                    names.add(var.getName());
                }
                Domain env = var.getEnv();
                Domain ker = var.getKer();
                while (iter.hasNext()) {
                    var = iter.next();
                    if (!(var instanceof TempSetVar)) {
                        names.add(var.getName());
                    }
                    env = env.intersection(var.getEnv());
                    ker = ker.union(var.getKer());
                    intGraph.union(card, var.getCardVar());
                }
                intGraph.union(card, tint(ker.size(), env.size()));
                Triple<String, Domain, Domain> key
                        = new Triple<>(joinNames(names), env, ker);
                for (IrSetVar coalesce : component) {
                    if (!(coalesce instanceof TempSetVar)) {
                        coalescedSetNameEnvKers.put(coalesce, key);
                    }
                }
            }
        }
        for (Set<IrIntVar> component : intGraph.connectedComponents()) {
            if (component.size() > 1) {
                Iterator<IrIntVar> iter = component.iterator();
                IrIntVar var = iter.next();
                List<String> names = new ArrayList<>(component.size());
                if (!(var instanceof TempIntVar)) {
                    names.add(var.getName());
                }
                Domain domain = var.getDomain();
                while (iter.hasNext()) {
                    var = iter.next();
                    if (!(var instanceof TempIntVar)) {
                        names.add(var.getName());
                    }
                    domain = domain.intersection(var.getDomain());
                }
                failIf(domain.isEmpty());
                IrIntVar coalesced = domainInt(joinNames(names), domain);
                for (IrIntVar coalesce : component) {
                    if (!coalesced.equals(coalesce) && !(coalesce instanceof TempIntVar)
                            && (names.size() > 1 || !coalesce.getDomain().equals(coalesced.getDomain()))) {
                        coalescedInts.put(coalesce, coalesced);
                    }
                }
            }
        }

        Map<Triple<String, Domain, Domain>, IrSetVar> setVarCache = new IdentityHashMap<>();
        for (IrSetVar setVar : setVars) {
            Triple<String, Domain, Domain> key = coalescedSetNameEnvKers.get(setVar);
            IrSetVar set = setVarCache.get(key);
            IrIntVar card = coalescedInts.get(setVar.getCardVar());
            if (set == null || card != null) {
                if (key != null || card != null) {
                    try {
                        set = set(
                                key == null ? setVar.getName() : key.getFst(),
                                key == null ? setVar.getEnv() : key.getSnd(),
                                key == null ? setVar.getKer() : key.getThd(),
                                card == null ? setVar.getCardVar() : card
                        );
                    } catch (IllegalSetException e) {
                        throw new UnsatisfiableException(e);
                    }
                }
            }
            if (set != null) {
                if (key != null) {
                    setVarCache.put(key, set);
                }
                if (!setVar.equals(set)) {
                    coalescedSets.put(setVar, set);
                }
            }
        }
        Map<List<IrIntVar>, IrStringVar> stringVarCache = new HashMap<>();
        for (IrStringVar stringVar : stringVars) {
            boolean changed = false;
            IrIntVar[] chars = new IrIntVar[stringVar.getCharVars().length];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = coalescedInts.get(stringVar.getCharVars()[i]);
                if (chars[i] == null) {
                    chars[i] = stringVar.getCharVars()[i];
                } else {
                    changed = true;
                }
            }
            IrIntVar length = coalescedInts.get(stringVar.getLengthVar());
            changed |= length != null;
            length = length == null ? stringVar.getLengthVar() : length;
            if (changed) {
                List<IrIntVar> key = new ArrayList<>();
                key.addAll(Arrays.asList(chars));
                key.addAll(Arrays.asList(length));
                IrStringVar string = stringVarCache.get(key);
                if (string == null) {
                    try {
                        string = string(stringVar.getName(), chars, length);
                        stringVarCache.put(key, string);
                    } catch (IllegalStringException e) {
                        throw new UnsatisfiableException(e);
                    }
                }
                coalescedStrings.put(stringVar, string);
            }
        }

        return new Triple<>(
                coalescedInts,
                coalescedSets,
                IrUtil.renameVariables(module, coalescedInts, coalescedSets, coalescedStrings));
    }

    private static IrSetVar tset(Domain env, Domain ker, Domain card) {
        try {
            return new TempSetVar(env, ker, tint(card));
        } catch (IllegalSetException e) {
            throw new UnsatisfiableException(e);
        }
    }

    private static Triple<DisjointSets<IrIntVar>, DisjointSets<IrSetVar>, DisjointSets<IrStringVar>>
            findEquivalences(Iterable<IrBoolExpr> constraints) {
        EquivalenceFinder finder = new EquivalenceFinder();
        for (IrBoolExpr constraint : constraints) {
            constraint.accept(finder, null);
        }
        return new Triple<>(finder.getIntGraph(), finder.getSetGraph(), finder.getStringGraph());
    }

    private static class EquivalenceFinder extends IrBoolExprVisitorAdapter<Void, Void> {

        private final DisjointSets<IrIntVar> intGraph = new DisjointSets<>();
        private final DisjointSets<IrSetVar> setGraph = new DisjointSets<>();
        private final DisjointSets<IrStringVar> stringGraph = new DisjointSets<>();

        public DisjointSets<IrIntVar> getIntGraph() {
            return intGraph;
        }

        public DisjointSets<IrSetVar> getSetGraph() {
            return setGraph;
        }

        public DisjointSets<IrStringVar> getStringGraph() {
            return stringGraph;
        }

        @Override
        public Void visit(IrRegister ir, Void a) {
            return null;
        }

        @Override
        public Void visit(IrBoolVar ir, Void a) {
            if (BoolDomain.TrueFalseDomain.equals(ir.getDomain())) {
                intGraph.union(ir, True);
            }
            return null;
        }

        @Override
        public Void visit(IrNot ir, Void a) {
            propagateInt(FalseDomain, ir.getExpr());
            return null;
        }

        @Override
        public Void visit(IrIfOnlyIf ir, Void a) {
            propagateEqual(ir.getLeft(), ir.getRight());
            return null;
        }

        @Override
        public Void visit(IrWithin ir, Void a) {
            propagateInt(ir.getRange(), ir.getValue());
            return null;
        }

        @Override
        public Void visit(IrCompare ir, Void a) {
            IrIntExpr left = ir.getLeft();
            IrIntExpr right = ir.getRight();
            switch (ir.getOp()) {
                case Equal:
                    propagateEqual(left, right);
                    break;
                case NotEqual:
                    propagateNotEqual(left, right);
                    break;
                case LessThan:
                    propagateLessThan(left, right);
                    break;
                case LessThanEqual:
                    propagateLessThanEqual(left, right);
                    break;
            }
            return null;
        }

        @Override
        public Void visit(IrArrayEquality ir, Void a) {
            switch (ir.getOp()) {
                case Equal:
                    propagateEqual(ir.getLeft(), ir.getRight());
                    break;
            }
            return null;
        }

        @Override
        public Void visit(IrSetEquality ir, Void a) {
            IrSetExpr left = ir.getLeft();
            IrSetExpr right = ir.getRight();
            if (IrSetEquality.Op.Equal.equals(ir.getOp())) {
                if (left instanceof IrSetVar && right instanceof IrSetVar) {
                    setGraph.union((IrSetVar) left, (IrSetVar) right);
                } else {
                    propagateSet(new PartialSet(left.getEnv(), left.getKer(), left.getCard()), right);
                    propagateSet(new PartialSet(right.getEnv(), right.getKer(), right.getCard()), left);
                }
            }
            return null;
        }

        @Override
        public Void visit(IrStringCompare ir, Void a) {
            IrStringExpr left = ir.getLeft();
            IrStringExpr right = ir.getRight();
            if (IrStringCompare.Op.Equal.equals(ir.getOp())) {
                if (left instanceof IrStringVar && right instanceof IrStringVar) {
                    stringGraph.union((IrStringVar) left, (IrStringVar) right);
                } else {
                    propagateString(asStringVar(left), right);
                    propagateString(asStringVar(right), left);
                }
            }
            return null;
        }

        @Override
        public Void visit(IrMember ir, Void a) {
            IrIntExpr element = ir.getElement();
            IrSetExpr set = ir.getSet();
            propagateInt(set.getEnv(), element);
            Domain ker = null;
            Domain card = null;
            Integer constant = IrUtil.getConstant(element);
            if (constant != null) {
                ker = set.getKer().insert(constant);
                card = set.getCard().boundLow(ker.size());
            } else {
                card = set.getCard().boundLow(1);
            }
            if (ker != null || card != null) {
                propagateSet(new PartialSet(null, ker, card), set);
            }
            return null;
        }

        @Override
        public Void visit(IrNotMember ir, Void a) {
            IrIntExpr element = ir.getElement();
            IrSetExpr set = ir.getSet();
            Domain domain = element.getDomain().difference(set.getKer());
            propagateInt(domain, element);
            Integer constant = IrUtil.getConstant(element);
            if (constant != null && set.getEnv().contains(constant)) {
                propagateEnv(set.getEnv().remove(constant), set);
            }
            return null;
        }

        @Override
        public Void visit(IrSubsetEq ir, Void a) {
            IrSetExpr sub = ir.getSubset();
            IrSetExpr sup = ir.getSuperset();
            propagateSet(new PartialSet(sup.getEnv(), null,
                    sub.getCard().boundHigh(sup.getCard().getHighBound())), sub);
            propagateSet(new PartialSet(null, sub.getKer(),
                    sup.getCard().boundLow(sub.getCard().getLowBound())), sup);
            return null;
        }

        @Override
        public Void visit(IrBoolChannel ir, Void a) {
            IrBoolExpr[] bools = ir.getBools();
            IrSetExpr set = ir.getSet();
            Domain env = set.getEnv();
            Domain ker = set.getKer();
            Domain trues = Domains.enumDomain(IntStream.range(0, bools.length).filter(i -> IrUtil.isTrue(bools[i])));
            Domain notFalses = env.removeAll(i -> i < 0 || i >= bools.length || IrUtil.isFalse(bools[i]));
            for (int i = 0; i < bools.length; i++) {
                if (bools[i] instanceof IrBoolVar && !IrUtil.isConstant(bools[i])) {
                    if (!env.contains(i)) {
                        intGraph.union((IrBoolVar) bools[i], False);
                    } else if (ker.contains(i)) {
                        intGraph.union((IrBoolVar) bools[i], True);
                    }
                }
            }
            propagateSet(new PartialSet(notFalses, trues, null), set);
            return null;
        }

        @Override
        public Void visit(IrIntChannel ir, Void a) {
            IrIntExpr[] ints = ir.getInts();
            IrSetExpr[] sets = ir.getSets();

            Domain kers = Domains.EmptyDomain;

            for (int i = 0; i < ints.length; i++) {
                TIntSet domain = new TIntHashSet();
                for (int j = 0; j < sets.length; j++) {
                    if (sets[j].getEnv().contains(i)) {
                        domain.add(j);
                    }
                }
                propagateInt(enumDomain(domain), ints[i]);
            }
            int lowCards = 0;
            int highCards = 0;
            for (IrSetExpr set : sets) {
                kers = kers.union(set.getKer());
                lowCards += set.getCard().getLowBound();
                highCards += set.getCard().getHighBound();
            }
            for (int i = 0; i < sets.length; i++) {
                int ii = i;
                Domain env = Domains.enumDomain(
                        IntStream.range(0, ints.length)
                        .filter(j -> ints[j].getDomain().contains(ii)));
                Domain ker = env.retainAll(j -> ints[j].getDomain().isConstant());
                env = env.difference(kers);
                env = env.union(sets[i].getKer());
                Domain card = boundDomain(
                        ints.length - highCards + sets[i].getCard().getHighBound(),
                        ints.length - lowCards + sets[i].getCard().getLowBound());
                propagateSet(new PartialSet(env, ker, card), sets[i]);
            }
            return null;
        }

        @Override
        public Void visit(IrSortStrings ir, Void a) {
            IrIntExpr[][] strings = ir.getStrings();
            for (int i = 0; i < strings.length - 1; i++) {
                if (ir.isStrict()) {
                    propagateLessThanString(strings[i], strings[i + 1]);
                } else {
                    propagateLessThanEqualString(strings[i], strings[i + 1]);
                }
            }
            return null;
        }

        private boolean isZero(Domain domain) {
            return domain.isConstant() && domain.getLowBound() == 0;
        }

        @Override
        public Void visit(IrSortSets ir, Void a) {
            IrSetExpr[] sets = ir.getSets();
            IrIntExpr[] bounds = ir.getBounds();
            IrIntExpr[] boundary = new IrIntExpr[sets.length + 1];
            boundary[0] = Zero;
            for (int i = 0; i < sets.length; i++) {
                if (isZero(boundary[i].getDomain())) {
                    boundary[i + 1] = Irs.card(sets[i]);
                } else if (isZero(sets[i].getCard())) {
                    boundary[i + 1] = boundary[i];
                } else {
                    boundary[i + 1] = bounds[i];
                    propagateEqual(add(boundary[i], Irs.card(sets[i])), boundary[i + 1]);
                }
                if (!boundary[i + 1].equals(bounds[i])) {
                    propagateEqual(boundary[i + 1], bounds[i]);
                }
                if (boundary[i].getDomain().isConstant()) {
                    int constant = boundary[i].getDomain().getLowBound();
                    if (sets[i].getCard().getLowBound() > 0 && !sets[i].getKer().contains(constant)) {
                        propagateKer(constantDomain(constant), sets[i]);
                    }
                }
                Domain env = null;
                if (boundary[i].getLowBound() < boundary[i + 1].getHighBound()) {
                    env = boundDomain(boundary[i].getLowBound(), boundary[i + 1].getHighBound() - 1);
                }
                Domain ker = null;
                if (boundary[i].getHighBound() < boundary[i + 1].getLowBound()) {
                    ker = boundDomain(boundary[i].getHighBound(), boundary[i + 1].getLowBound() - 1);
                }
                if (env != null || ker != null) {
                    propagateSet(new PartialSet(env, ker, null), sets[i]);
                }
            }
//            for (int i = 0; i < sets.length; i++) {
//                propagators.add(new PropContinuous(sets[i], setCards[i]));
//            }
            return null;
        }

        @Override
        public Void visit(IrSortStringsChannel ir, Void a) {
            IrIntExpr[][] strings = ir.getStrings();
            IrIntExpr[] ints = ir.getInts();
            DisjointSets<Integer> equivalenceClasses = new DisjointSets<>();
            TIntList[] largerThans = new TIntList[strings.length];
            for (int i = 0; i < strings.length; i++) {
                largerThans[i] = new TIntArrayList(4);
                equivalenceClasses.union(i, i);
            }
            for (int i = 0; i < strings.length; i++) {
                for (int j = i + 1; j < strings.length; j++) {
                    switch (IrUtil.compareString(strings[i], strings[j])) {
                        case EQ:
                            propagateEqual(ints[i], ints[j]);
                            equivalenceClasses.union(i, j);
                            break;
                        case LT:
                            propagateLessThan(ints[i], ints[j]);
                            largerThans[j].add(i);
                            break;
                        case LE:
                            propagateLessThanEqual(ints[i], ints[j]);
                            largerThans[j].add(i);
                            break;
                        case GT:
                            propagateLessThan(ints[j], ints[i]);
                            largerThans[i].add(j);
                            break;
                        case GE:
                            propagateLessThanEqual(ints[j], ints[i]);
                            largerThans[i].add(j);
                            break;
                        case UNKNOWN:
                            largerThans[i].add(j);
                            largerThans[j].add(i);
                            break;
                    }
                }
            }
            Collection<Set<Integer>> equivalenceComponents = equivalenceClasses.connectedComponents();
            for (Set<Integer> equivalenceComponent : equivalenceComponents) {
                int representative = equivalenceClasses.representative(equivalenceComponent.iterator().next());
                TIntHashSet largerThanClasses = new TIntHashSet(largerThans[representative].size());
                largerThans[representative].forEach(x -> largerThanClasses.add(equivalenceClasses.representative(x)) || true);
                for (int i : equivalenceComponent) {
                    propagateInt(ints[i].getDomain().boundHigh(largerThanClasses.size()), ints[i]);
                }
            }
            for (int i = 0; i < ints.length; i++) {
                for (int j = i + 1; j < ints.length; j++) {
                    switch (IrUtil.compare(ints[i], ints[j])) {
                        case EQ:
                            propagateEqualString(strings[i], strings[j]);
                            break;
                        case LT:
                            propagateLessThanString(strings[i], strings[j]);
                            break;
                        case LE:
                            propagateLessThanEqualString(strings[i], strings[j]);
                            break;
                        case GT:
                            propagateLessThanString(strings[j], strings[i]);
                            break;
                        case GE:
                            propagateLessThanEqualString(strings[j], strings[i]);
                            break;
                    }
                }
            }
            return null;
        }

        @Override
        public Void visit(IrAllDifferent ir, Void a) {
            IrIntExpr[] operands = ir.getOperands();
            for (int i = 0; i < operands.length; i++) {
                for (int j = i + 1; j > operands.length; j++) {
                    propagateNotEqual(operands[i], operands[j]);
                }
            }
            return null;
        }

        @Override
        public Void visit(IrSelectN ir, Void a) {
            IrBoolExpr[] bools = ir.getBools();
            IrIntExpr n = ir.getN();
            for (int i = 0; i < bools.length; i++) {
                if (IrUtil.isTrue(bools[i]) && i >= n.getDomain().getLowBound()) {
                    propagateInt(boundDomain(i + 1, bools.length), n);
                } else if (IrUtil.isFalse(bools[i]) && i < n.getDomain().getHighBound()) {
                    propagateInt(boundDomain(0, i), n);
                }
            }
            for (int i = 0; i < n.getDomain().getLowBound() && i < bools.length; i++) {
                propagateInt(TrueDomain, bools[i]);
            }
            for (int i = n.getDomain().getHighBound(); i >= 0 && i < bools.length; i++) {
                propagateInt(FalseDomain, bools[i]);
            }
            return null;
        }

        @Override
        public Void visit(IrPrefix ir, Void a) {
            propagatePrefix(ir.getPrefix(), ir.getWord());
            return null;
        }

        @Override
        public Void visit(IrSuffix ir, Void a) {
            propagateSuffix(ir.getSuffix(), ir.getWord());
            return null;
        }

        private void propagateEqual(IrIntExpr left, IrIntExpr right) {
            if (left instanceof IrIntVar && right instanceof IrIntVar) {
                intGraph.union((IrIntVar) left, (IrIntVar) right);
            } else {
                propagateInt(left.getDomain(), right);
                propagateInt(right.getDomain(), left);
            }
        }

        private IrIntExpr get(IrIntArrayExpr array, int index) {
            if (array instanceof IrIntArrayVar) {
                IrIntArrayVar var = (IrIntArrayVar) array;
                return var.getArray()[index];
            }
            if (array instanceof IrSubarray) {
                IrSubarray subarray = (IrSubarray) array;
                if (subarray.getIndex().getDomain().isConstant() && index < subarray.getSublength().getLowBound()) {
                    return get(subarray.getArray(), subarray.getIndex().getLowBound() + index);
                }
            }
            return null;
        }

        private void propagateEqual(IrIntArrayExpr a, IrIntArrayExpr b) {
            for (int i = 0; i < a.length(); i++) {
                IrIntExpr aI = get(a, i);
                IrIntExpr bI = get(b, i);
                if (aI != null && bI != null) {
                    propagateEqual(aI, bI);
                } else if (aI != null) {
                    propagateInt(b.getDomains()[i], aI);
                } else if (bI != null) {
                    propagateInt(a.getDomains()[i], bI);
                }
            }
        }

        private void propagateEqualString(IrIntExpr[] a, IrIntExpr[] b) {
            for (int i = 0; i < a.length; i++) {
                propagateEqual(a[i], b[i]);
            }
        }

        private void propagateNotEqual(IrIntExpr left, IrIntExpr right) {
            Integer constant = IrUtil.getConstant(left);
            if (constant != null) {
                Domain remove = right.getDomain().remove(constant);
                propagateInt(remove, right);
            }
            constant = IrUtil.getConstant(right);
            if (constant != null) {
                Domain remove = left.getDomain().remove(constant);
                propagateInt(remove, left);
            }
        }

        private void propagateLessThan(IrIntExpr left, IrIntExpr right) {
            Domain leftDomain = left.getDomain();
            Domain rightDomain = right.getDomain();
            if (leftDomain.getHighBound() >= rightDomain.getHighBound()) {
                propagateInt(leftDomain.boundHigh(rightDomain.getHighBound() - 1), left);
            }
            if (rightDomain.getLowBound() <= leftDomain.getLowBound()) {
                propagateInt(rightDomain.boundLow(leftDomain.getLowBound() + 1), right);
            }
        }

        private void propagateLessThanString(IrIntExpr[] a, IrIntExpr[] b) {
            propagateLessThanString(a, b, 0);
        }

        private void propagateLessThanString(IrIntExpr[] a, IrIntExpr[] b, int index) {
            assert a.length == b.length;
            failIf(index == a.length);
            switch (IrUtil.compare(a[index], b[index])) {
                case EQ:
                    propagateLessThanString(a, b, index + 1);
                    return;
                case LT:
                    return;
                case GT:
                    fail();
                    return;
                case LE:
                case GE:
                case UNKNOWN:
                    switch (IrUtil.compareString(a, b, index + 1)) {
                        case EQ:
                        case GT:
                        case GE:
                            propagateLessThan(a[index], b[index]);
                            return;
                        case LT:
                        case LE:
                        case UNKNOWN:
                            propagateLessThanEqual(a[index], b[index]);
                            return;
                        default:
                            throw new IllegalStateException();
                    }
                default:
                    throw new IllegalStateException();
            }
        }

        private void propagateLessThanEqual(IrIntExpr left, IrIntExpr right) {
            Domain leftDomain = left.getDomain();
            Domain rightDomain = right.getDomain();
            if (leftDomain.getHighBound() > rightDomain.getHighBound()) {
                propagateInt(left.getDomain().boundHigh(right.getDomain().getHighBound()), left);
            }
            if (rightDomain.getLowBound() < leftDomain.getLowBound()) {
                propagateInt(right.getDomain().boundLow(left.getDomain().getLowBound()), right);
            }
        }

        private void propagateLessThanEqualString(IrIntExpr[] a, IrIntExpr[] b) {
            propagateLessThanEqualString(a, b, 0);
        }

        private void propagateLessThanEqualString(IrIntExpr[] a, IrIntExpr[] b, int index) {
            if (index == a.length || index == b.length) {
                return;
            }
            switch (IrUtil.compare(a[index], b[index])) {
                case EQ:
                    propagateLessThanEqualString(a, b, index + 1);
                    return;
                case LT:
                    return;
                case GT:
                    fail();
                    return;
                case LE:
                case GE:
                case UNKNOWN:
                    switch (IrUtil.compareString(a, b, index + 1)) {
                        case EQ:
                        case LT:
                        case LE:
                        case GE:
                        case UNKNOWN:
                            propagateLessThanEqual(a[index], b[index]);
                            return;
                        case GT:
                            propagateLessThan(a[index], b[index]);
                            return;
                        default:
                            throw new IllegalStateException();
                    }
                default:
                    throw new IllegalStateException();
            }
        }

        private void propagateInt(Domain left, IrIntExpr right) {
            if (right.getDomain().isSubsetOf(left)) {
                return;
            }
            if (right instanceof IrIntVar) {
                Domain domain = left.intersection(right.getDomain());
                failIf(domain.isEmpty());
                intGraph.union((IrIntVar) right, tint(domain));
            } else if (right instanceof IrMinus) {
                propagateInt(left.minus(), ((IrMinus) right).getExpr());
            } else if (right instanceof IrCard) {
                propagateCard(left, ((IrCard) right).getSet());
            } else if (right instanceof IrAdd) {
                IrAdd add = (IrAdd) right;
                IrIntExpr[] addends = add.getAddends();
                if (addends.length == 1) {
                    propagateInt(left.offset(-add.getOffset()), addends[0]);
                } else {
                    for (IrIntExpr addend : addends) {
                        Domain domain = addend.getDomain();
                        Domain bound = domain.boundBetween(
                                left.getLowBound() - right.getDomain().getHighBound() + domain.getHighBound(),
                                left.getHighBound() - right.getDomain().getLowBound() + domain.getLowBound());
                        propagateInt(bound, addend);
                    }
                }
            } else if (right instanceof IrElement) {
                IrElement element = (IrElement) right;
                Domain dom = element.getIndex().getDomain().retainAll(i
                        -> left.intersects(element.getArray().getDomains()[i]));
                propagateInt(dom, element.getIndex());
            } else if (right instanceof IrCount) {
                IrCount count = (IrCount) right;
                int value = count.getValue();
                int possibles = 0;
                int mandatories = 0;
                for (IrIntExpr element : count.getArray()) {
                    if (element.getDomain().contains(value)) {
                        if (element.getDomain().isConstant()) {
                            mandatories++;
                        } else {
                            possibles++;
                        }
                    }
                }
                if (possibles + mandatories <= left.getLowBound()) {
                    Domain valueDomain = constantDomain(value);
                    for (IrIntExpr element : count.getArray()) {
                        if (element.getDomain().contains(value) && element.getDomain().size() > 1) {
                            propagateInt(valueDomain, element);
                        }
                    }
                } else if (mandatories >= left.getHighBound()) {
                    for (IrIntExpr element : count.getArray()) {
                        if (element.getDomain().contains(value) && element.getDomain().size() > 1) {
                            propagateInt(element.getDomain().remove(value), element);
                        }
                    }
                }
            } else if (right instanceof IrLength) {
                IrLength length = (IrLength) right;
                propagateString(tstring(chars(length.getString()), tint(left)), length.getString());
            }
        }

        private void propagateSet(PartialSet left, IrSetExpr right) {
            left.updateMask(right.getEnv(), right.getKer(), right.getCard());
            if (left.hasMask()) {
                if (right instanceof IrSetVar) {
                    propagateSetVar(left, (IrSetVar) right);
                } else if (right instanceof IrSingleton) {
                    propagateSingleton(left, (IrSingleton) right);
                } else if (right instanceof IrArrayToSet) {
                    propagateArrayToSet(left, (IrArrayToSet) right);
                } else if (right instanceof IrSetElement) {
                    propagateSetElement(left, (IrSetElement) right);
                } else if (right instanceof IrJoinRelation) {
                    propagateJoinRelation(left, (IrJoinRelation) right);
                } else if (right instanceof IrJoinFunction) {
                    propagateJoinFunction(left, (IrJoinFunction) right);
                } else if (right instanceof IrSetUnion) {
                    propagateSetUnion(left, (IrSetUnion) right);
                } else if (right instanceof IrOffset) {
                    propagateOffset(left, (IrOffset) right);
                }
            }
        }

        private void propagateSetVar(PartialSet left, IrSetVar right) {
            Domain env = right.getEnv();
            Domain ker = right.getKer();
            Domain card = right.getCard();
            if (left.isEnvMask()) {
                env = left.getEnv();
            }
            if (left.isKerMask()) {
                ker = left.getKer();
            }
            if (left.isCardMask()) {
                card = left.getCard();
            }
            IrSetVar set = tset(env, ker, card);
            setGraph.union(right, set);
        }

        private void propagateSingleton(PartialSet left, IrSingleton right) {
            if (left.isKerMask()) {
                Domain ker = left.getKer();
                if (ker.isConstant()) {
                    propagateInt(ker, right.getValue());
                }
            } else if (left.isEnvMask()) {
                Domain env = left.getEnv();
                propagateInt(env, right.getValue());
            }
        }

        private void propagateArrayToSet(PartialSet left, IrArrayToSet right) {
            if (left.isEnvMask()) {
                Domain env = left.getEnv();
                for (IrIntExpr child : right.getArray()) {
                    propagateInt(env, child);
                }
            }
            if (left.isKerMask()) {
                PrimitiveIterator.OfInt iter = left.getKer().difference(right.getKer()).iterator();
                while (iter.hasNext()) {
                    int val = iter.next();
                    Util.findUnique(right.getArray(), x -> x.getDomain().contains(val))
                            .ifPresent(index
                                    -> propagateInt(constantDomain(val), index));
                }
            }
        }

        private void propagateSetElement(PartialSet left, IrSetElement right) {
            IrIntExpr index = right.getIndex();
            IrSetArrayExpr array = right.getArray();
            Domain dom = index.getDomain().retainAll(
                    i -> (!left.isEnvMask() || array.getKers()[i].isSubsetOf(left.getEnv()))
                    && (!left.isKerMask() || left.getKer().isSubsetOf(array.getEnvs()[i]))
                    && (!left.isCardMask() || left.getCard().intersects(array.getCards()[i])));
            propagateInt(dom, index);
        }

        private void propagateJoinRelation(PartialSet left, IrJoinRelation right) {
            if (right.isInjective()) {
                IrSetArrayExpr children = right.getChildren();
                if (left.isEnvMask() || left.isCardMask()) {
                    if (children instanceof IrSetArrayVar) {
                        Domain env = left.getEnv();
                        Domain card = left.isCardMask() ? boundDomain(0, left.getCard().getHighBound()) : null;
                        PrimitiveIterator.OfInt iter = right.getTake().getKer().iterator();
                        PartialSet set = new PartialSet(env, null, card);
                        while (iter.hasNext()) {
                            propagateSet(set, ((IrSetArrayVar) children).getArray()[iter.next()]);
                        }
                    }
                }
                if (left.isKerMask()) {
                    PrimitiveIterator.OfInt iter = left.getKer().difference(right.getKer()).iterator();
                    while (iter.hasNext()) {
                        int val = iter.next();
                        OptionalInt index = Util.findUnique(
                                right.getTake().getEnv().iterator(),
                                j -> children.getEnvs()[j].contains(val));
                        if (index.isPresent()) {
                            propagateKer(constantDomain(index.getAsInt()), right.getTake());
                            if (children instanceof IrSetArrayVar) {
                                propagateKer(constantDomain(val), ((IrSetArrayVar) children).getArray()[index.getAsInt()]);
                            }
                        }
                    }
                }
                if (left.isCardMask()) {
                    IrSetExpr take = right.getTake();
                    int lb = left.getCard().getLowBound();
                    int ub = left.getCard().getHighBound();
                    int[] envLbs = new int[take.getEnv().size() - take.getKer().size()];
                    int[] envUbs = new int[envLbs.length];
                    int kerMinCard = 0;
                    int kerMaxCard = 0;
                    int env = 0;
                    PrimitiveIterator.OfInt iter = take.getEnv().iterator();
                    while (iter.hasNext()) {
                        int i = iter.next();
                        if (take.getKer().contains(i)) {
                            kerMinCard += children.getCards()[i].getLowBound();
                            kerMaxCard += children.getCards()[i].getHighBound();
                        } else {
                            envLbs[env] = children.getCards()[i].getLowBound();
                            envUbs[env] = children.getCards()[i].getHighBound();
                            env++;
                        }
                    }
                    Arrays.sort(envLbs);
                    Arrays.sort(envUbs);
                    int i;
                    for (i = 0; i < envLbs.length && (kerMinCard < ub || envLbs[i] == 0); i++) {
                        kerMinCard += envLbs[i];
                    }
                    int high = i + take.getKer().size();
                    for (i = envUbs.length - 1; i >= 0 && kerMaxCard < lb; i--) {
                        kerMaxCard += envUbs[i];
                    }
                    int low = envUbs.length - 1 - i + take.getKer().size();
                    if (low > take.getCard().getLowBound() || high < take.getCard().getHighBound()) {
                        propagateCard(boundDomain(low, high), take);
                    }
                    if (children instanceof IrSetArrayVar) {
                        iter = take.getKer().iterator();
                        while (iter.hasNext()) {
                            int ker = iter.next();
                            propagateCard(boundDomain(
                                    lb - kerMaxCard + children.getCards()[ker].getHighBound(),
                                    ub - kerMinCard + children.getCards()[ker].getLowBound()),
                                    ((IrSetArrayVar) children).getArray()[ker]);
                        }
                    }
                }
            }
        }

        private void propagateJoinFunction(PartialSet left, IrJoinFunction right) {
            if (left.isEnvMask()) {
                if (right.getRefs() instanceof IrIntArrayVar) {
                    IrIntExpr[] rightArray = ((IrIntArrayVar) right.getRefs()).getArray();
                    Domain env = left.getEnv();
                    PrimitiveIterator.OfInt iter = right.getTake().getKer().iterator();
                    while (iter.hasNext()) {
                        propagateInt(env, rightArray[iter.next()]);
                    }
                }
            }
            if (left.isKerMask()) {
                if (right.getRefs() instanceof IrIntArrayVar) {
                    IrIntExpr[] rightArray = ((IrIntArrayVar) right.getRefs()).getArray();
                    PrimitiveIterator.OfInt iter = left.getKer().difference(right.getKer()).iterator();
                    while (iter.hasNext()) {
                        int val = iter.next();
                        Util.findUnique(
                                right.getTake().getEnv().iterator(),
                                j -> right.getRefs().getDomains()[j].contains(val))
                                .ifPresent(index -> {
                                    propagateKer(constantDomain(index), right.getTake());
                                    propagateInt(constantDomain(val), rightArray[index]);
                                });
                    }
                }
            }
            if (left.isCardMask()) {
                Domain card = left.getCard();
                IrSetExpr take = right.getTake();
                int low = Math.max(take.getKer().size(), card.getLowBound());
                int high = Math.min(take.getEnv().size(),
                        right.hasGlobalCardinality()
                                ? card.getHighBound() * right.getGlobalCardinality()
                                : take.getCard().getHighBound());
                if (low > take.getCard().getLowBound() || high < take.getCard().getHighBound()) {
                    propagateCard(boundDomain(low, high), take);
                }
            }
        }

        private void propagateSetUnion(PartialSet left, IrSetUnion right) {
            if (left.isEnvMask() || left.isCardMask()) {
                IrSetExpr[] operands = right.getOperands();
                Domain env = left.getEnv();
                if (right.isDisjoint() && left.isCardMask()) {
                    int lowCards = 0;
                    int highCards = 0;
                    for (IrSetExpr operand : operands) {
                        lowCards += operand.getCard().getLowBound();
                        highCards += operand.getCard().getHighBound();
                    }
                    for (IrSetExpr operand : operands) {
                        Domain card = boundDomain(
                                left.getCard().getLowBound() - highCards + operand.getCard().getHighBound(),
                                left.getCard().getHighBound() - lowCards + operand.getCard().getLowBound());
                        PartialSet set = new PartialSet(env, null, card);
                        propagateSet(set, operand);
                    }
                } else {
                    Domain card = left.isCardMask() ? boundDomain(0, left.getCard().getHighBound()) : null;
                    PartialSet set = new PartialSet(env, null, card);
                    for (IrSetExpr operand : operands) {
                        propagateSet(set, operand);
                    }
                }
            }
            if (left.isKerMask()) {
                PrimitiveIterator.OfInt iter = left.getKer().difference(right.getKer()).iterator();
                while (iter.hasNext()) {
                    int val = iter.next();
                    Util.<IrSetExpr>findUnique(
                            right.getOperands(),
                            x -> x.getEnv().contains(val))
                            .ifPresent(index
                                    -> propagateKer(constantDomain(val), index));
                }
            }
        }

        private void propagateOffset(PartialSet left, IrOffset right) {
            int offset = right.getOffset();
            Domain env = left.isEnvMask() ? left.getEnv().offset(-offset) : null;
            Domain ker = left.isKerMask() ? left.getKer().offset(-offset) : null;
            Domain card = left.isCardMask() ? left.getCard() : null;
            propagateSet(new PartialSet(env, ker, card), right.getSet());
        }

        private void propagateEnv(Domain left, IrSetExpr right) {
            propagateSet(env(left), right);
        }

        private void propagateKer(Domain left, IrSetExpr right) {
            propagateSet(ker(left), right);
        }

        private void propagateCard(Domain left, IrSetExpr right) {
            propagateSet(card(left), right);
        }

        private void propagatePrefix(IrStringExpr prefix, IrStringExpr word) {
            {
                IrIntVar[] chars = chars(word).clone();
                for (int i = 0; i < chars.length; i++) {
                    if (i >= prefix.getLength().getLowBound()) {
                        chars[i] = tint(chars[i]).add(0);
                    }
                }
                propagateString(
                        tstring(chars).boundHighLength(word.getLength().getHighBound()),
                        prefix);
            }
            {
                IrIntVar[] chars = chars(word).clone();
                IrIntVar[] prefixChars = chars(prefix);
                System.arraycopy(prefixChars, 0, chars, 0,
                        Math.min(prefix.getLength().getLowBound(),
                                Math.min(prefixChars.length, chars.length)));
                propagateString(
                        tstring(chars).boundLowLength(prefix.getLength().getLowBound()),
                        word);
            }
        }

        private void propagateSuffix(IrStringExpr suffix, IrStringExpr word) {
            failIf(word.getLength().getHighBound() < suffix.getLength().getLowBound());
            {
                IrIntVar[] chars = new IrIntVar[suffix.getChars().length];
                int low = Math.max(0,
                        word.getLength().getLowBound()
                        - suffix.getLength().getHighBound());
                int high = word.getLength().getHighBound()
                        - suffix.getLength().getLowBound();
                for (int i = 0; i < chars.length; i++) {
                    int ilow = low + i;
                    if (ilow >= word.getChars().length) {
                        chars[i] = Zero;
                    } else {
                        int ihigh = Math.min(word.getChars().length - 1, high + i);
                        IrIntVar ichar = charAt(word, ilow);
                        for (int j = ilow + 1; j <= ihigh; j++) {
                            ichar = tint(ichar).union(word.getChars()[j]);
                        }
                        chars[i] = ilow < suffix.getLength().getLowBound()
                                ? ichar : tint(ichar).add(0);
                    }
                }
                propagateString(
                        tstring(chars).boundHighLength(word.getLength().getHighBound()),
                        suffix);
            }
            {
                IrIntVar[] chars = chars(word).clone();
                IrIntVar charDomain = charAt(suffix, suffix.getLength().getHighBound() - 1);
                for (int i = suffix.getLength().getHighBound() - 1;
                        i > suffix.getLength().getLowBound();
                        i--) {
                    charDomain = tint(charDomain).union(suffix.getChars()[i - 1]);
                }
                for (int i = 0; i < suffix.getLength().getLowBound(); i++) {
                    charDomain = tint(charDomain).copy().union(suffix.getChars()[suffix.getLength().getLowBound() - i - 1]);
                    int index = word.getLength().getHighBound() - i - 1;
                    if (index >= word.getLength().getLowBound()) {
                        charDomain = tint(charDomain).add(0);
                    }
                    chars[word.getLength().getHighBound() - i - 1] = charDomain;
                }
                propagateString(
                        tstring(chars).boundLowLength(suffix.getLength().getLowBound()),
                        word);
            }
        }

        private void propagateString(IrStringVar left, IrStringExpr right) {
            if (right instanceof IrStringVar) {
                propagateStringVar(left, (IrStringVar) right);
            } else if (right instanceof IrConcat) {
                propagateConcat(left, (IrConcat) right);
            }
        }

        private IrStringVar asStringVar(IrStringExpr expr) {
            if (expr instanceof IrStringVar) {
                return (IrStringVar) expr;
            }
            IrIntVar chars[] = chars(expr);
            IrIntVar length = tint(
                    expr.getLength().boundBetween(0, chars.length));
            return tstring(chars, length);
        }

        private void propagateStringVar(IrStringVar left, IrStringVar right) {
            assert !(right instanceof TempStringVar);
            if (!(left instanceof TempStringVar)
                    || ((TempStringVar) left).isRestrictive(right)) {
                stringGraph.union(right, left);
            }
        }

        private void propagateConcat(IrStringVar left, IrConcat right) {
            propagatePrefix(asStringVar(right.getLeft()), left);
            propagateSuffix(asStringVar(right.getRight()), left);
            propagateInt(left.getLength(),
                    add(length(right.getLeft()), length(right.getRight())));
        }
    }

    private static IrIntVar charAt(IrStringExpr expr, int index) {
        if (expr instanceof IrStringVar) {
            return ((IrStringVar) expr).getCharVars()[index];
        }
        return tint(expr.getChars()[index]);
    }

    private static IrIntVar[] chars(IrStringExpr expr) {
        if (expr instanceof IrStringVar) {
            return ((IrStringVar) expr).getCharVars();
        }
        return mapTint(expr.getChars());
    }

    /**
     * The model is unsatisfiable.
     */
    private static void fail() {
        throw new UnsatisfiableException();
    }

    private static void failIf(boolean fail) {
        if (fail) {
            fail();
        }
    }

    private static TempIntVar tint(IrIntVar var) {
        if (var instanceof TempIntVar) {
            return (TempIntVar) var;
        }
        try {
            return new TempIntVar(var.getDomain());
        } catch (IllegalIntException e) {
            throw new UnsatisfiableException(e);
        }
    }

    private static TempIntVar tint(Domain domain) {
        try {
            return new TempIntVar(domain);
        } catch (IllegalIntException e) {
            throw new UnsatisfiableException(e);
        }
    }

    private static TempIntVar tint(int low, int high) {
        try {
            return new TempIntVar(boundDomain(low, high));
        } catch (IllegalIntException e) {
            throw new UnsatisfiableException(e);
        }
    }

    private static IrIntVar[] mapTint(Domain[] domains) {
        IrIntVar[] vars = new IrIntVar[domains.length];
        for (int i = 0; i < vars.length; i++) {
            vars[i] = tint(domains[i]);
        }
        return vars;
    }

    private static PartialSet env(Domain env) {
        return new PartialSet(env, null, null);
    }

    private static PartialSet ker(Domain ker) {
        return new PartialSet(null, ker, null);
    }

    private static PartialSet card(Domain card) {
        return new PartialSet(null, null, card);
    }

    private static class PartialSet {

        private final Domain env;
        private final Domain ker;
        private final Domain card;
        private byte mask;

        PartialSet(Domain env, Domain ker, Domain card) {
            assert env != null || ker != null || card != null;
            this.env = env;
            this.ker = ker;
            this.card = card;
        }

        Domain getEnv() {
            return env;
        }

        Domain getKer() {
            return ker;
        }

        Domain getCard() {
            return card;
        }

        boolean isEnvMask() {
            return (mask & 1) == 1;
        }

        boolean isKerMask() {
            return (mask & 2) == 2;
        }

        boolean isCardMask() {
            return (mask & 4) == 4;
        }

        boolean hasMask() {
            return mask != 0;
        }

        void updateMask(Domain env, Domain ker, Domain card) {
            if (this.env != null && !env.isSubsetOf(this.env)) {
                mask |= 1;
            }
            if (this.ker != null && !this.ker.isSubsetOf(ker)) {
                mask |= 2;
            }
            if (this.card != null && !card.isSubsetOf(this.card)) {
                mask |= 4;
            }
        }

        @Override
        public String toString() {
            return (env != null ? "env=" + env : "")
                    + (ker != null ? "ker=" + ker : "")
                    + (card != null ? "card=" + card : "");
        }
    }

    private static TempStringVar tstring(IrIntVar[] chars) {
        return tstring(chars, tint(0, chars.length));
    }

    private static TempStringVar tstring(IrIntVar[] chars, IrIntVar length) {
        return new TempStringVar(chars, length);
    }

    private static int count = 0;

    private static class TempIntVar extends IrIntVar {

        TempIntVar(Domain domain) {
            super("temp" + count++, domain);
        }

        TempIntVar add(int value) {
            if (getDomain().contains(value)) {
                return this;
            }
            return new TempIntVar(getDomain().insert(value));
        }

        TempIntVar boundLow(int lb) {
            if (lb <= getDomain().getLowBound()) {
                return this;
            }
            Domain domain = getDomain().boundLow(lb);
            failIf(domain.isEmpty());
            return new TempIntVar(domain);
        }

        TempIntVar boundHigh(int hb) {
            if (hb >= getDomain().getHighBound()) {
                return this;
            }
            Domain domain = getDomain().boundHigh(hb);
            failIf(domain.isEmpty());
            return new TempIntVar(domain);
        }

        TempIntVar boundBetween(int lb, int hb) {
            if (lb <= getDomain().getLowBound() && hb >= getDomain().getHighBound()) {
                return this;
            }
            Domain domain = getDomain().boundBetween(lb, hb);
            failIf(domain.isEmpty());
            return new TempIntVar(domain);
        }

        TempIntVar union(Domain union) {
            Domain domain = getDomain().union(union);
            if (domain.equals(getDomain())) {
                return this;
            }
            return new TempIntVar(domain);
        }

        TempIntVar copy() {
            return new TempIntVar(getDomain());
        }
    }

    private static class TempSetVar extends IrSetVar {

        TempSetVar(Domain env, Domain ker, IrIntVar card) {
            super("temp" + count++, env, ker, card);
        }
    }

    private static class TempStringVar extends IrStringVar {

        TempStringVar(IrIntVar[] chars, IrIntVar length) {
            super("temp" + count++, chars, length);
        }

        boolean isRestrictive(IrStringVar var) {
            for (int i = 0; i < Math.min(getChars().length, var.getChars().length); i++) {
                if (!var.getChars()[i].isSubsetOf(getChars()[i])) {
                    return true;
                }
            }
            return !var.getLength().isSubsetOf(getLength());
        }

        TempStringVar boundLowLength(int lb) {
            if (lb <= getLength().getLowBound()) {
                return this;
            }
            return new TempStringVar(getCharVars(), tint(getLengthVar()).boundLow(lb));
        }

        TempStringVar boundHighLength(int hb) {
            if (hb >= getLength().getHighBound()) {
                return this;
            }
            return new TempStringVar(getCharVars(), tint(getLengthVar()).boundHigh(hb));
        }
    }
}
