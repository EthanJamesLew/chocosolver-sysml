package org.plantuml.ast;

import org.sysml.ast.SysmlExpr;
import org.sysml.ast.SysmlExprVisitor;

import java.io.IOException;

/**
 * Main PlantUML Program Element
 *
 * This is likely quite wrong. For our use case, we consider a PlantUML program as a collection
 * of objects and connections.
 */
public class PlantumlProgram implements PlantumlExpr {
    private PlantumlObject[] objects;
    private PlantumlConnection[] connections;

    public PlantumlProgram() {
    }

    @Override
    public <A, B> B accept(PlantumlExprVisitor<A, B> visitor, A a) throws IOException {
        return visitor.visit(this, a);
    }
}
