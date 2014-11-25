package org.clafer.choco.constraint.propagator;

import java.lang.reflect.Field;
import solver.Solver;
import solver.constraints.Constraint;
import solver.constraints.ICF;
import solver.constraints.Propagator;
import solver.constraints.PropagatorPriority;
import solver.constraints.set.PropIntMemberSet;
import solver.constraints.set.SCF;
import solver.exception.ContradictionException;
import solver.variables.IntVar;
import solver.variables.SetVar;
import solver.variables.VF;
import solver.variables.Variable;
import solver.variables.events.IntEventType;
import util.ESat;

/**
 *
 * @author jimmy
 */
public class PropIntMemberNonemptySet extends Propagator<Variable> {

    private final IntVar element;
    private final SetVar set;
    private final IntVar setCard;

    /**
     * watch1 and watch2 points are two unique integers in env(set) and
     * dom(element). As long as these two elements exists, then the intersection
     * between env(set) and dom(element) is greater than one. When the
     * intersection is one, then that integer must be the element.
     */
    private int watch1, watch2;

    public PropIntMemberNonemptySet(IntVar element, SetVar set, IntVar setCard) {
        super(new Variable[]{set, element, setCard}, PropagatorPriority.BINARY, false);
        this.element = element;
        this.set = set;
        this.setCard = setCard;
        this.watch1 = SetVar.END;
        this.watch2 = SetVar.END;
    }

    /**
     * @return {@code true} if at most one watch left, {@code false} otherwise
     */
    private boolean updateWatches() {
        watch1 = element.contains(watch1) ? watch1 : SetVar.END;
        watch2 = element.contains(watch2) ? watch2 : SetVar.END;
        if (watch1 == SetVar.END || watch2 == SetVar.END) {
            // watch1 == SetVar.End => watch2 == SetVar.End
            if (watch1 == SetVar.END) {
                watch1 = watch2;
                watch2 = SetVar.END;
            }
            for (int j = set.getEnvelopeFirst(); j != SetVar.END; j = set.getEnvelopeNext()) {
                if (element.contains(j)) {
                    if (watch1 == SetVar.END) {
                        watch1 = j;
                    } else if (watch1 != j) {
                        // Found the second watch.
                        assert (watch2 == SetVar.END);
                        watch2 = j;
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void watchFilter() throws ContradictionException {
        if (updateWatches()) {
            if (watch1 != SetVar.END && watch2 == SetVar.END) {
                set.addToKernel(watch1, aCause);
                element.instantiateTo(watch1, aCause);
                setPassive();
            } else if (watch1 == SetVar.END) {
                contradiction(element, "");
            }
        }
    }

    @Override
    public void propagate(int evtmask) throws ContradictionException {
        if (setCard.getLB() > 0) {
            PropUtil.domSubsetEnv(element, set, aCause);
            if (element.isInstantiated()) {
                set.addToKernel(element.getValue(), aCause);
                setPassive();
            } else {
                watchFilter();
            }
        } else {
            if (updateWatches() && watch1 == SetVar.END) {
                setCard.instantiateTo(0, aCause);
            }
        }
    }

    @Override
    public ESat isEntailed() {
        if (setCard.getLB() > 0) {
            if (!PropUtil.isDomIntersectEnv(element, set)) {
                return ESat.FALSE;
            }
            if (PropUtil.isDomSubsetKer(element, set)) {
                return ESat.TRUE;
            }
            return ESat.UNDEFINED;
        }
        return setCard.getUB() <= 0 ? ESat.TRUE : ESat.UNDEFINED;
    }

public static void main(String[] args) throws Exception {
    Solver solver = new Solver();
    SetVar s = VF.set("s", 0, 2, solver);
    IntVar i = VF.enumerated("i", 0, 2, solver);

    Field w1 = PropIntMemberSet.class.getDeclaredField("watchLit1");
    w1.setAccessible(true);
    Field w2 = PropIntMemberSet.class.getDeclaredField("watchLit2");
    w2.setAccessible(true);

    Constraint c = SCF.member(i, s);
    solver.post(c);
    solver.propagate();
    System.out.println(s + " : " + i);
    System.out.println("watch1=" + w1.get(c.getPropagator(0)) + ", watch2=" + w2.get(c.getPropagator(0)));

    solver.post(ICF.arithm(i, "!=", 1));
    solver.propagate();
    System.out.println(s + " : " + i);
    System.out.println("watch1=" + w1.get(c.getPropagator(0)) + ", watch2=" + w2.get(c.getPropagator(0)));
}
}
