package com.github.sybila

fun main(args: Array<String>) {

    val thresholds = "0.0, 0.0053351117039013, 0.012004001333777926, 0.01867289096365455, 0.025341780593531177, 0.0320106702234078, 0.04001333777925975, 0.0480160053351117, 0.05601867289096365, 0.0640213404468156, 0.07202400800266755, 0.08136045348449483, 0.0906968989663221, 0.10003334444814937, 0.10936978992997665, 0.12004001333777925, 0.13071023674558185, 0.14138046015338446, 0.15338446148716237, 0.1653884628209403, 0.17872624208069354, 0.1920640213404468, 0.20673557852617538, 0.2227409136378793, 0.23874624874958317, 0.2560853617872624, 0.274758252750917, 0.29476492164054685, 0.31610536845615206, 0.33877959319773254, 0.3627875958652884, 0.3894631543847949, 0.4174724908302767, 0.4481493831277092, 0.48149383127709233, 0.5175058352784261, 0.549516505501834, 0.5801933977992664, 0.6082027342447482, 0.6348782927642547, 0.6588862954318105, 0.6815605201733911, 0.7029009669889963, 0.7229076358786262, 0.7415805268422807, 0.7589196398799599, 0.7749249749916638, 0.7909303101033678, 0.8056018672890963, 0.8189396465488495, 0.8322774258086029, 0.8442814271423807, 0.8562854284761586, 0.8669556518839613, 0.8776258752917638, 0.8882960986995665, 0.8976325441813937, 0.906968989663221, 0.9163054351450483, 0.9256418806268756, 0.9336445481827276, 0.9416472157385795, 0.9496498832944315, 0.9576525508502833, 0.9656552184061353, 0.972324108036012, 0.9789929976658885, 0.9856618872957652, 0.9923307769256419, 0.9976658886295431, 1.0030010003334444, 1.0083361120373457, 1.013671223741247, 1.017672557519173, 1.021673891297099, 1.025675225075025, 1.0296765588529508, 1.0323441147049015, 1.0350116705568522, 1.0376792264088028, 1.0390130043347783, 1.0403467822607535, 1.041680560186729, 1.0430143381127042, 1.0456818939646548, 1.0483494498166055, 1.0510170056685562, 1.055018339446482, 1.0590196732244082, 1.063021007002334, 1.06702234078026, 1.0723574524841613, 1.0776925641880626, 1.083027675891964, 1.0883627875958652, 1.0950316772257418, 1.1017005668556186, 1.1083694564854951, 1.1150383461153717, 1.1217072357452484, 1.1297099033011002, 1.1377125708569522, 1.1457152384128042, 1.1537179059686562, 1.1630543514504834, 1.1723907969323106, 1.181727242414138, 1.1910636878959653, 1.201733911303768, 1.2124041347115704, 1.223074358119373, 1.235078359453151, 1.2470823607869288, 1.2604201400466821, 1.2737579193064354, 1.288429476492164, 1.3031010336778925, 1.3191063687895965, 1.3364454818272757, 1.3551183727909302, 1.37512504168056, 1.3964654884961654, 1.419139713237746, 1.4431477159053017, 1.4684894964988329, 1.4964988329443147, 1.5258419473157718, 1.5578526175391796, 1.592530843614538, 1.629876625541847, 1.6698899633211068, 1.7139046348782927, 1.7619206402134044, 1.813937979326442, 1.8699566522174056, 1.9313104368122707, 1.9979993331110368, 2.0700233411137043, 2.148716238746249, 2.2354118039346447, 2.3301100366788927, 2.432810936978993, 2.5461820606868955, 2.671557185728576, 2.8089363121040347, 2.9609869956652215, 3.1277092364121373, 3.3131043681227075, 3.5185061687229076, 3.746582194064688, 3.9999999999999996"
            .split(',').map { it.trim().toDouble() }.toMutableSet()
    val min = 0.0.also { thresholds.add(it) }
    val max = 4.0.also { thresholds.add(it) }

    val count = 150
    val step: Double = (max - min) / count
    var k = min
    while (k < max) {
        //print(java.lang.String.format("%.5f, ", k))
        thresholds.add(k)
        k += step
    }
    println(thresholds.toList().sorted().joinToString(separator = ", ") { java.lang.String.format("%.6f", it) })
}