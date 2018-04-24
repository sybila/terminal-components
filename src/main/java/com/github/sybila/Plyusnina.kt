package com.github.sybila

import com.github.sybila.ode.generator.AbstractOdeFragment
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.model.OdeModel

@Suppress("FunctionName", "NOTHING_TO_INLINE")
object Plyusnina : AbstractOdeFragment<Params>(OdeModel(
        parameters = emptyList(),
        variables = listOf(
                OdeModel.Variable(
                        name = "Hi",
                        range = 0.0 to 10.0,
                        thresholds = split(5, 0.0, 10.0),
                        varPoints = null,
                        equation = emptyList()
                ),
                OdeModel.Variable(
                        name = "Ho",
                        range = 0.0 to 10.0,
                        thresholds = split(5, 0.0, 10.0),
                        varPoints = null,
                        equation = emptyList()
                ),
                OdeModel.Variable(
                        name = "ATP",
                        range = 0.0 to 1.0,
                        thresholds = split(5, 0.0, 1.0),
                        varPoints = null,
                        equation = emptyList()
                ),
                OdeModel.Variable(
                        name = "NADH",
                        range = 0.0 to 1.0,
                        thresholds = split(5, 0.0, 1.0),
                        varPoints = null,
                        equation = emptyList()
                ),
                OdeModel.Variable(
                        name = "NADPH",
                        range = 0.0 to 1.0,
                        thresholds = split(5, 0.0, 1.0),
                        varPoints = null,
                        equation = emptyList()
                ),
                OdeModel.Variable(
                        name = "PQH2",
                        range = 0.0 to 1.0,
                        thresholds = split(5, 0.0, 1.0),
                        varPoints = null,
                        equation = emptyList()
                ),
                OdeModel.Variable(
                        name = "fd",
                        range = 0.0 to 2.0,
                        thresholds = split(5, 0.0, 2.0),
                        varPoints = null,
                        equation = emptyList()
                ),
                OdeModel.Variable(
                        name = "pc",
                        range = 0.0 to 2.0,
                        thresholds = split(5, 0.0, 2.0),
                        varPoints = null,
                        equation = emptyList()
                )
        )
), true, RectangleSolver(Rectangle(doubleArrayOf()))) {

    private const val i_Hi = 0
    private const val i_Ho = 1
    private const val i_ATP = 2
    private const val i_NADH = 3
    private const val i_NADPH = 4
    private const val i_PQH2 = 5
    private const val i_fd = 6
    private const val i_pc = 7

    override fun getVertexColor(vertex: Int, dimension: Int, positive: Boolean): Params? {
        val derivation = when (dimension) {
            i_Hi -> vertex.eval_Hi()
            i_Ho -> vertex.eval_Ho()
            i_ATP -> vertex.eval_ATP()
            i_NADH -> vertex.eval_NADH()
            i_NADPH -> vertex.eval_NADPH()
            i_PQH2 -> vertex.eval_PQH2()
            i_fd -> vertex.eval_fd()
            i_pc -> vertex.eval_pc()
            else -> error("Unknown dimension $dimension")
        }
        return if ((positive && derivation > 0.0) || (!positive && derivation < 0.0)) tt else ff
    }

    private fun Int.eval_Hi(): Double = V_hi() / Hi()
    private fun Int.eval_Ho(): Double = V_Ho() / Ho()
    private fun Int.eval_ATP(): Double = V_ATP() / ATP()
    private fun Int.eval_NADH(): Double = V_NADH() / NADH()
    private fun Int.eval_NADPH(): Double = V_NADPH() / NADPH()
    private fun Int.eval_PQH2(): Double = V_PQH2() / PQH2()
    private fun Int.eval_fd(): Double = V_fd() / fd()
    private fun Int.eval_pc(): Double = V_pc() / pc()

    private inline fun Int.V_hi(): Double = Vx2() + Vx2i() + Vcyt1() + Vcyc() + Vnd2() + Vcox2() - Va2() + VHi - (kHi * Hi())
    private inline fun Int.V_Ho(): Double = -(Vx2() + Vx2i()) - Vcyc() - Vf2() - Vnd1() - Vnh1() - Vcox1() + Va2() + VHo - kHo * Ho()
    private inline fun Int.V_ATP(): Double = -Va1() + Vatp_gl - (katp_gl * ATP())
    private inline fun Int.V_NADH(): Double = -Vnh1() + Vnad_gl - (knad_gl * NAD())
    private inline fun Int.V_NADPH(): Double = Vf2() - Vnd1() + Vnadp_gl - (knadp_gl * NADPH())
    private inline fun Int.V_PQH2(): Double = Vx2() + Vx2i() - Vcyt1() + Vnd2() + Vnh2() + Vsdh2() - Vcyd1()
    private inline fun Int.V_fd(): Double = Vf1() - (Vy2() + Vy2i()) + Vcyc()
    private inline fun Int.V_pc(): Double = Vcyt2() - (Vy2() + Vy2i()) - Vcox1()
    private inline fun Int.Va2(): Double = (qa2() * a1()) - (qa_2() * a0())
    private inline fun Int.Vcox2(): Double = (qcox2 * cox1() - qcox_2() * cox0()) * CX
    private inline fun Int.Vnd2(): Double = (qnd2() * nd1() - qnd_2() * nd0()) * ND
    private inline fun Int.Vcyc(): Double = qcyc() * cyt0() - q_cyc() * cyt1()
    private inline fun Int.Vcyt1(): Double = qcyt1() * cyt0() - qcyt_1 * cyt1()
    private inline fun Int.Vx2i(): Double = (qx2() * x1i() - qx_2() * x0i()) * PS2
    private inline fun Int.Vx2(): Double = (qx2() * x1() - qx_2() * x0a()) * PS2
    private inline fun Int.Vf2(): Double = (qf2() * f1()) - (qf_2() * f0())
    private inline fun Int.Vnd1(): Double = (qnd1() * nd0() - qnd_1() * nd1()) * ND
    private inline fun Int.Vnh1(): Double = (qnh1() * nh0() - qnh_1() * nh1()) * NH
    private inline fun Int.Vcox1(): Double = (qcox1() * cox0() - qcox_1() * cox1()) * CX
    private inline fun Int.Va1(): Double = qa1() * a0() - qa_1() * a1()
    private inline fun Int.NAD(): Double = NADtot - NADH()
    private inline fun Int.Vnh2(): Double = (qnh2() * nh1() - qnh_2() * nh0()) * NH
    private inline fun Int.Vsdh2(): Double = (qsdh2() * sdh1() - qsdh_2() * sdh0()) * SD
    private inline fun Int.Vcyd1(): Double = (qcyd1() * cyd0() - qcyd_1() * cyd1()) * CD
    private inline fun Int.Vf1(): Double = qf1() * f0() - qf_1() * f1()
    private inline fun Int.Vy2(): Double = (qy2() * y1() - qy_2() * y0()) * PS1
    private inline fun Int.Vy2i(): Double = (qy2() * y1i() - qy_2() * y0i()) * PS1
    private inline fun Int.Vcyt2(): Double = qcyt2 * cyt1() - qcyt_2() * cyt0()
    private inline fun Int.qnd2(): Double = knd2 * PQ()
    private inline fun Int.nd1(): Double = (ndtot * (qnd1() + qnd_2()))/(qnd1() + qnd_1() + qnd2() + qnd_2())
    private inline fun Int.qnd_2(): Double = knd_2 * PQH2() * Hi()
    private inline fun Int.nd0(): Double = (ndtot * (qnd_1() + qnd2()))/(qnd1() + qnd_1() + qnd2() + qnd_2())
    private inline fun Int.qcyc(): Double = kcyc * fd_r() * Ho()
    private inline fun Int.cyt0(): Double = (cyttot * (qcyt_1 + q_cyc() + qcyt2))/(qcyt1() + qcyc() + qcyt_1 + q_cyc() + qcyt2 + qcyt_2())
    private inline fun Int.q_cyc(): Double = k_cyc * fd() * Hi()
    private inline fun Int.cyt1(): Double = (cyttot * (qcyt1() + q_cyc() + qcyt_2()))/(qcyt1() + qcyc() + qcyt_1 + q_cyc() + qcyt2 + qcyt_2())
    private inline fun Int.qcyt1(): Double = kcyt1 * PQH2() * pc_ox()
    private inline fun Int.x1i(): Double = (((qxtr)/(qx_tr() + qxtr)) * xtot * Rx4())/(Rx5())
    private inline fun Int.qx_2(): Double = kx_2 * PQH2() * Hi() * Hi() * Math.sqrt(O2)
    private inline fun Int.x0i(): Double = (((qxtr)/(qx_tr() + qxtr)) * xtot * Rx3())/(Rx5())
    private inline fun Int.qx2(): Double = kx2 * PQ() * Ho() * Ho()
    private inline fun Int.x1(): Double = (((qx_tr())/(qx_tr() + qxtr)) * xtot * Rx2())/(Rx5())
    private inline fun Int.x0a(): Double = (((qx_tr())/(qx_tr() + qxtr)) * xtot * Rx1())/(Rx5())
    private inline fun Int.qf2(): Double = kf2 * NADP() * Ho()
    private inline fun Int.f1(): Double = (ftot * (qf1() + qf_2()))/(qf1() + qf_1() + qf2() + qf_2())
    private inline fun Int.qf_2(): Double = kf_2 * NADPH()
    private inline fun Int.f0(): Double = (ftot * (qf_1() + qf2()))/(qf1() + qf_1() + qf2() + qf_2())
    private inline fun Int.qnd1(): Double = knd1 * NADPH() * Ho() * Ho()
    private inline fun Int.qnd_1(): Double = knd_1 * NADP()
    private inline fun Int.qnh1(): Double = knh1 * NADH() * Ho()
    private inline fun Int.nh0(): Double = (nhtot * (qnh_1() + qnh2()))/(qnh1() + qnh_1() + qnh2() + qnh_2())
    private inline fun Int.qnh_1(): Double = knh_1 * NAD()
    private inline fun Int.nh1(): Double = (nhtot * (qnh1() + qnh_2()))/(qnh1() + qnh_1() + qnh2() + qnh_2())
    private inline fun Int.qcox1(): Double = kcox1 * pc() * Ho() * Ho()
    private inline fun Int.qcox_1(): Double = kcox_1 * pc_ox()
    private inline fun Int.qa1(): Double = ka1 * ATP()
    private inline fun Int.qa_1(): Double = ka_1 * ADP()
    private inline fun Int.qa2(): Double = ka2 * Hi() * Hi() * Hi()
    private inline fun Int.a1(): Double = (atot * (qa1() + qa_2()))/(qa1() + qa_1() + qa2() + qa_2())
    private inline fun Int.qa_2(): Double = ka_2 * Ho() * Ho() * Ho()
    private inline fun Int.a0(): Double = (atot * (qa_1() + qa2()))/(qa1() + qa_1() + qa2() + qa_2())
    private inline fun Int.cox1(): Double = (coxtot * (qcox1() + qcox_2()))/(qcox1() + qcox_1() + qcox2 + qcox_2())
    private inline fun Int.qcox_2(): Double = kcox_2 * Hi() * Math.pow(O2, 1/4.0)
    private inline fun Int.cox0(): Double = (coxtot * (qcox_1() + qcox2))/(qcox1() + qcox_1() + qcox2 + qcox_2())
    private inline fun Int.qnh2(): Double = knh2 * PQ()
    private inline fun Int.qnh_2(): Double = knh_2 * PQH2()
    private inline fun Int.qsdh2(): Double = ksdh2 * PQ()
    private inline fun Int.sdh1(): Double = (sdhtot * (qsdh1 + qsdh_2()))/(qsdh1 + qsdh_1 + qsdh2() + qsdh_2())
    private inline fun Int.qsdh_2(): Double = ksdh_2 * PQH2()
    private inline fun Int.sdh0(): Double = (sdhtot * (qsdh_1 + qsdh2()))/(qsdh1 + qsdh_1 + qsdh2() + qsdh_2())
    private inline fun Int.qcyd1(): Double = kcyd1 * PQH2()
    private inline fun Int.qcyd_1(): Double = kcyd_1 * PQ()
    private inline fun Int.cyd0(): Double = (cydtot * (qcyd_1() + qcyd2))/(qcyd1() + qcyd_1() + qcyd2 + qcyd_2)
    private inline fun Int.cyd1(): Double = (cydtot * (qcyd1() + qcyd_2))/(qcyd1() + qcyd_1() + qcyd2 + qcyd_2)
    private inline fun Int.qf1(): Double = kf1 * fd_r() * fd_r()
    private inline fun Int.qf_1(): Double = kf_1 * fd() * fd()
    private inline fun Int.qy2(): Double = ky2 * pc() * fd()
    private inline fun Int.y1(): Double = ((((qy_tr))/(qy_tr + qytr)) * ytot * Ry2())/(Ry5())
    private inline fun Int.qy_2(): Double = ky_2 * fd_r() * pc_ox()
    private inline fun Int.y0(): Double = (((qy_tr)/(qy_tr + qytr)) * ytot * Ry1())/(Ry5())
    private inline fun Int.y1i(): Double = (((qytr)/(qy_tr + qytr)) * ytot * Ry4())/(Ry5())
    private inline fun Int.y0i(): Double = (((qytr)/(qy_tr + qytr)) * ytot * Ry3())/(Ry5())
    private inline fun Int.qcyt_2(): Double = kcyt_2 * PQ() * pc() * Hi() * Hi()
    private inline fun Int.PQ(): Double = PQtot - PQH2()
    private inline fun Int.fd_r(): Double = fdtot - fd()
    private inline fun Int.ADP(): Double = ATPtot - ATP()
    private inline fun Int.pc_ox(): Double = pctot - pc()
    private inline fun Int.Rx1(): Double = (qx_1i + qx2i()) * qxtr + (qx_1 + qx2()) * qx_tr() + (qx1i + qx_1i + qx2i() + qx_2i()) * (qx_1 + qx2())
    private inline fun Int.Rx2(): Double = (qx1i + qx_2i()) * qxtr + (qx1 + qx_2()) * qx_tr() + (qx1i + qx_1i + qx2i() + qx_2i()) * (qx1 + qx_2())
    private inline fun Int.Rx3(): Double = (qx_1i + qx2i()) * qxtr + (qx_1 + qx2()) * qx_tr() + (qx1 + qx_1 + qx2() + qx_2()) * (qx_1i + qx2i())
    private inline fun Int.Rx4(): Double = (qx1i + qx_2i()) * qxtr + (qx1 + qx_2()) * qx_tr() + (qx1 + qx_1 + qx2() + qx_2()) * (qx1i + qx_2i())
    private inline fun Int.Rx5(): Double = (qx1i + qx_1i + qx2i() + qx_2i()) * qxtr + (qx1 + qx_1 + qx2() + qx_2()) * qx_tr() + (qx1i + qx_1i + qx2i() + qx_2i())*(qx1 + qx_1 + qx2()+ qx_2())
    private inline fun Int.qx_tr(): Double = kx_tr * Fun()
    private inline fun Int.NADP(): Double = NADPtot - NADPH()
    private inline fun Int.Ry1(): Double = (qy_1i + qy2i()) * qytr + (qy_1 + qy2()) * qy_tr + (qy1i + qy_1i + qy2i() + qy_2i()) * (qy_1 + qy2())
    private inline fun Int.Ry2(): Double = (qy1i + qy_2i()) * qytr + (qy1 + qy_2()) * qy_tr + (qy1i + qy_1i + qy2i() + qy_2i()) * (qy1 + qy_2())
    private inline fun Int.Ry3(): Double = (qy_1i + qy2i()) * qytr + (qy_1 + qy2()) * qy_tr + (qy1 + qy_1 + qy2() + qy_2()) * (qy_1i + qy2i())
    private inline fun Int.Ry4(): Double = (qy1i + qy_2i()) * qytr + (qy1 + qy_2()) * qy_tr + (qy1 + qy_1 + qy2() + qy_2()) * (qy1i + qy_2i())
    private inline fun Int.Ry5(): Double = (qy1i + qy_1i + qy2i() + qy_2i()) * qytr + (qy1 + qy_1 + qy2() + qy_2()) * qy_tr + (qy1i + qy_1i + qy2i() + qy_2i()) * (qy1 + qy_1 + qy2() + qy_2())
    private inline fun Int.qx2i(): Double = qx2()
    private inline fun Int.qx_2i(): Double = qx_2()
    private inline fun Int.Fun(): Double = 1+(Vm * PQH2() * PQH2())/(Km + PQH2() * PQH2())
    private inline fun Int.qy2i(): Double = qy2()
    private inline fun Int.qy_2i(): Double = qy_2()

    private const val kHi: Double = 0.01
    private const val VHi: Double = 0.05
    private const val VHo: Double = 0.05
    private const val kHo: Double = 0.01
    private const val Vatp_gl: Double = 0.01
    private const val katp_gl: Double = 0.001
    private const val Vnad_gl: Double = 0.01
    private const val knad_gl: Double = 0.001
    private const val Vnadp_gl: Double = 0.01
    private const val knadp_gl: Double = 0.001
    private const val ND: Double = 1.0
    private const val PS1: Double = 1.0
    private const val PS2: Double = 1.0
    private const val NH: Double = 1.0
    private const val CX: Double = 1.0
    private const val SD: Double = 1.0
    private const val NADtot: Double = 1.0
    private const val ndtot: Double = 1.0
    private const val knd_2: Double = 0.1
    private const val kcyc: Double = 0.01
    private const val knd2: Double = 0.2
    private const val CD: Double = 1.0
    private const val cyttot: Double = 1.0
    private const val k_cyc: Double = 0.001
    private const val coxtot: Double = 1.0
    private const val kcox1: Double = 0.2
    private const val kcox_1: Double = 0.01
    private const val kcox2: Double = 0.1
    private const val kcox_2: Double = 0.01
    private const val kcyt1: Double = 0.1
    private const val kcyt_1: Double = 0.01
    private const val ka1: Double = 0.1
    private const val ka_1: Double = 0.01
    private const val ka2: Double = 0.1
    private const val ka_2: Double = 0.01
    private const val kx2: Double = 0.3
    private const val kx_2: Double = 1.0
    private const val knh1: Double = 0.1
    private const val knh_1: Double = 0.1
    private const val knd1: Double = 0.2
    private const val knd_1: Double = 0.1
    private const val kf2: Double = 0.22
    private const val kf_2: Double = 0.01
    private const val atot: Double = 1.0
    private const val xtot: Double = 1.0
    private const val nhtot: Double = 1.0
    private const val ftot: Double = 1.0
    private const val O2: Double = 1.0
    private const val knh2: Double = 0.1
    private const val knh_2: Double = 0.1
    private const val ksdh2: Double = 0.1
    private const val sdhtot: Double = 1.0
    private const val ksdh_2: Double = 0.1
    private const val kcyd1: Double = 0.1
    private const val kcyd_1: Double = 0.1
    private const val cydtot: Double = 1.0
    private const val kf1: Double = 0.1
    private const val kf_1: Double = 0.01
    private const val ky2: Double = 0.1
    private const val ky_2: Double = 0.5
    private const val ytot: Double = 1.0
    private const val kcyt2: Double = 0.1
    private const val kcyt_2: Double = 0.01
    private const val PQtot: Double = 1.0
    private const val fdtot: Double = 1.0
    private const val pctot: Double = 1.0
    private const val ATPtot: Double = 1.0
    private const val kx_tr: Double = 0.1
    private const val kxtr: Double = 0.01
    private const val PBS: Double = 0.3
    private const val NADPtot: Double = 1.0
    private const val ksdh1: Double = 0.1
    private const val Suc: Double = 4.5
    private const val ksdh_1: Double = 0.1
    private const val Fum: Double = 0.4
    private const val kcyd2: Double = 0.2
    private const val kcyd_2: Double = 0.1
    private const val ky_tr: Double = 0.01
    private const val kytr: Double = 0.1
    private const val kx1i: Double = 2.0
    private const val light: Double = 1.0
    private const val kx_1: Double = 1.0
    private const val kx1: Double = 4.0
    private const val Vm: Double = 0.0
    private const val Km: Double = 0.5
    private const val ky1i: Double = 2.5
    private const val ky_1: Double = 1.0
    private const val ky1: Double = 1.5
    private const val qcox2: Double = kcox2
    private const val qcyt_1: Double = kcyt_1
    private const val qcyt2: Double = kcyt2
    private const val qxtr: Double = kxtr * PBS
    private const val qsdh1: Double = ksdh1 * Suc
    private const val qsdh_1: Double = ksdh_1 * Fum
    private const val qcyd2: Double = kcyd2 * 1 // Math.sqrt(O2) = sqrt(1) = 1
    private const val qcyd_2: Double = kcyd_2
    private const val qy_tr: Double = ky_tr
    private const val qytr: Double = kytr * PBS
    private const val qx1i: Double = kx1i * light
    private const val qx_1: Double = kx_1
    private const val qx1: Double = kx1 * light
    private const val qy1i: Double = ky1i * light
    private const val qy_1: Double = ky_1
    private const val qy1: Double = ky1 * light
    private const val qy_1i: Double = qy_1
    private const val qx_1i: Double = qx_1


    private fun Int.Hi(): Double = e_var(i_Hi)
    private fun Int.Ho(): Double = e_var(i_Ho)
    private fun Int.ATP(): Double = e_var(i_ATP)
    private fun Int.NADH(): Double = e_var(i_NADH)
    private fun Int.NADPH(): Double = e_var(i_NADPH)
    private fun Int.PQH2(): Double = e_var(i_PQH2)
    private fun Int.fd(): Double = e_var(i_fd)
    private fun Int.pc(): Double = e_var(i_pc)

    private fun Int.e_var(i: Int): Double =
            model.variables[i].thresholds[encoder.vertexCoordinate(this, i)]

}

private fun split(count: Int, min: Double, max: Double): List<Double> {
    val result = ArrayList<Double>()
    val step = (max - min) / count
    var i = 0
    while (min + i * step < max) {
        result.add(min + i * step)
        i += 1
    }
    result.add(max)
    return result
}