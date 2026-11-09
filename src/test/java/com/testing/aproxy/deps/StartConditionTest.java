package com.testing.aproxy.deps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testing.aproxy.deps.StartCondition.All;
import com.testing.aproxy.deps.StartCondition.Any;
import com.testing.aproxy.deps.StartCondition.Atom;
import com.testing.aproxy.deps.StartCondition.Type;

import org.junit.jupiter.api.Test;

class StartConditionTest {

    @Test
    void readsAtomsWithLookBackAndInstance() {
        Atom atom = (Atom) StartCondition.parse(" s(TEST_FEED_SYNC,10.00) ");
        assertThat(atom.type()).isEqualTo(Type.SUCCESS);
        assertThat(atom.name()).isEqualTo("TEST_FEED_SYNC");
        assertThat(atom.lookBack()).isEqualTo("10.00");
        assertThat(atom.text()).isEqualTo("s(TEST_FEED_SYNC,10.00)");

        Atom other = (Atom) StartCondition.parse("success(LOAD.JOB#1^PRD)");
        assertThat(other.name()).isEqualTo("LOAD.JOB#1");
        assertThat(other.instance()).isEqualTo("PRD");
        assertThat(other.lookBack()).isNull();
    }

    @Test
    void andBindsTighterThanOrAndParenthesesGroup() {
        Any any = (Any) StartCondition.parse("s(A) & s(B) | f(C)");
        assertThat(any.parts()).hasSize(2);
        assertThat(any.parts().getFirst()).isInstanceOf(All.class);

        All all = (All) StartCondition.parse("s(A) AND (d(B) or t(C)) and n(D)");
        assertThat(all.parts()).hasSize(3);
        assertThat(all.parts().get(1)).isInstanceOf(Any.class);
        assertThat(((Atom) all.parts().get(2)).type()).isEqualTo(Type.NOTRUNNING);
    }

    @Test
    void readsComparisonsOfExitCodesAndVariables() {
        All all = (All) StartCondition.parse("e(CHECK) > 1 & exitcode(X)<>0 & v(MODE) = \"FULL RUN\" & value(N)!=3");
        Atom exit = (Atom) all.parts().get(0);
        assertThat(exit.type()).isEqualTo(Type.EXITCODE);
        assertThat(exit.operator()).isEqualTo(">");
        assertThat(exit.value()).isEqualTo("1");
        assertThat(((Atom) all.parts().get(1)).operator()).isEqualTo("!=");
        Atom variable = (Atom) all.parts().get(2);
        assertThat(variable.type()).isEqualTo(Type.VALUE);
        assertThat(variable.value()).isEqualTo("FULL RUN");
        assertThat(((Atom) all.parts().get(3)).value()).isEqualTo("3");
    }

    @Test
    void refusesWhatItDoesNotUnderstand() {
        assertThatThrownBy(() -> StartCondition.parse("x(A)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StartCondition.parse("s(A) &")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StartCondition.parse("(s(A)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StartCondition.parse("e(A)")).hasMessageContaining("comparison");
    }
}
