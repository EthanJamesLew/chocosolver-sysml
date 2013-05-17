package org.clafer.ir;

import org.clafer.Check;

/**
 *
 * @author jimmy
 */
public class IrIfOnlyIf extends IrAbstractBool implements IrBoolExpr {

    private final IrBoolExpr left, right;

    IrIfOnlyIf(IrBoolExpr left, IrBoolExpr right, IrBoolDomain domain) {
        super(domain);
        this.left = Check.notNull(left);
        this.right = Check.notNull(right);
    }

    public IrBoolExpr getLeft() {
        return left;
    }

    public IrBoolExpr getRight() {
        return right;
    }

    @Override
    public IrBoolExpr negate() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isNegative() {
        return false;
    }

    @Override
    public <A, B> B accept(IrBoolExprVisitor<A, B> visitor, A a) {
        return visitor.visit(this, a);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof IrIfOnlyIf) {
            IrIfOnlyIf other = (IrIfOnlyIf) obj;
            return left.equals(other.left) && right.equals(other.right) && super.equals(other);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return left.hashCode() ^ right.hashCode();
    }

    @Override
    public String toString() {
        return left + " <=> " + right;
    }
}
