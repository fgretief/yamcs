package org.yamcs.xtce;

import java.util.stream.Collectors;

public class ANDedConditions extends ExpressionList {
    private static final long serialVersionUID = 6301730763127090210L;

    @Override
    public boolean isMet(CriteriaEvaluator evaluator) {
        for (BooleanExpression exp : expressions) {
            if (!exp.isMet(evaluator)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toExpressionString() {
        return expressions.stream()
                .map(BooleanExpression::toExpressionString)
                .collect(Collectors.joining(" and "));
    }
}
