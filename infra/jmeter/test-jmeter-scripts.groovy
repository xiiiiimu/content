import javax.script.*
import org.apache.jmeter.util.JMeterUtils
import org.apache.jmeter.threads.JMeterVariables
import org.apache.jmeter.samplers.SampleResult
import org.codehaus.groovy.control.CompilerConfiguration
JMeterUtils.loadJMeterProperties('tools/apache-jmeter-5.6.3/bin/jmeter.properties')
assert CompilerConfiguration.DEFAULT.optimizationOptions.indy == true
File out=new File('target/jmeter-script-check-'+UUID.randomUUID());assert out.mkdirs()
Properties props=new Properties();props.setProperty('clientKeyMode','csv');props.setProperty('purchaseStartsPerSecond','0');props.setProperty('threads','1')
JMeterVariables vars=new JMeterVariables();['startEpochMs':'0','barrierEnabled':'true','barrierGroupSize':'1','barrierTimeoutMs':'120000','barrierStatsPath':new File(out,'barrier-stats.json').path,'clientKey':'jm-c1-u11'].each{k,v->vars.put(k,v)}
SampleResult prev=new SampleResult();prev.setResponseData('{"code":"SOLD_OUT"}','UTF-8')
def engine=new ScriptEngineManager().getEngineByName('groovy');def xml=new XmlParser().parse('infra/jmeter/commerce-sale.jmx');int checked=0;int executed=0
xml.depthFirst().findAll{it instanceof Node && it.name().toString().startsWith('JSR223')}.each{node->
 def code=node.stringProp.find{it.@name=='script'}?.text()
 if(code){def bindings=engine.createBindings();bindings.put('props',props);bindings.put('vars',vars);bindings.put('prev',prev);def compiled=((Compilable)engine).compile(code);checked++;if(!(node.@testname in ['mix-poll-counter','stop-if-terminal'])){compiled.eval(bindings);executed++}}
}
assert vars.get('effClientKey')=='jm-c1-u11';assert vars.get('businessCode')=='SOLD_OUT'
def barrier=new groovy.json.JsonSlurper().parse(new File(out,'barrier-stats.json'));assert barrier.readyCount==1;assert !barrier.timedOut;assert barrier.groovyIndy=='true'
println "OFFLINE_SCRIPT_REGRESSION_PASS compiled=$checked executed=$executed indy=true (no HTTP performed)"
