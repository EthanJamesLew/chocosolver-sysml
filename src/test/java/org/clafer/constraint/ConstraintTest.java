package org.clafer.constraint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import solver.Solver;
import solver.search.strategy.IntStrategyFactory;
import solver.search.strategy.SetStrategyFactory;
import solver.search.strategy.strategy.StrategiesSequencer;
import solver.variables.IntVar;
import solver.variables.SetVar;
import solver.variables.Variable;

/**
 *
 * @author jimmy
 */
public abstract class ConstraintTest {
    
    private final Random rand = new Random();
    
    public int nextInt(int n) {
        return rand.nextInt(n);
    }
    
    public Set<String> solveAll(Solver solver) {
        Set<String> solutions = new HashSet<String>();
        if (solver.findSolution()) {
            do {
                if (!solutions.add(solver.toString())) {
                    throw new AssertionError();
                }
            } while (solver.nextSolution());
        }
        return solutions;
    }
    
    public long quickCheckModel(Solver solver) {
        List<IntVar> intVars = new ArrayList<IntVar>();
        List<SetVar> setVars = new ArrayList<SetVar>();
        for (Variable var : solver.getVars()) {
            if (var instanceof IntVar) {
                intVars.add((IntVar) var);
            } else if (var instanceof SetVar) {
                setVars.add((SetVar) var);
            } else {
                throw new IllegalStateException();
            }
        }
        solver.set(
                new StrategiesSequencer(solver.getEnvironment(),
                SetStrategyFactory.setLex(setVars.toArray(new SetVar[setVars.size()])),
                IntStrategyFactory.random(intVars.toArray(new IntVar[intVars.size()]), System.nanoTime())));
        return solver.findAllSolutions();
    }
}
