// Opt-in injector initialization only. No credentials and no successful purchase.
// The one negative-auth probe and script compilation happen before the buyer barrier.
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy
import org.apache.jmeter.protocol.http.control.HeaderManager
import org.apache.jmeter.protocol.http.control.Header
import org.apache.jmeter.extractor.JSR223PostProcessor
import javax.xml.parsers.DocumentBuilderFactory
import groovy.json.JsonOutput

SampleResult.setIgnore()
if (!Boolean.parseBoolean(props.getProperty('transportBootstrap','false'))) return
def target = new URI(props.getProperty('apiBase'))
if (!(target.host in ['127.0.0.1','localhost']) || target.scheme != 'http')
    throw new IllegalStateException('Transport bootstrap is restricted to local HTTP test service')
def sampler = new HTTPSamplerProxy()
sampler.setImplementation('HttpClient4')
sampler.setProtocol(target.scheme); sampler.setDomain(target.host)
sampler.setPort(target.port == -1 ? 80 : target.port)
sampler.setPath('/api/sale-campaigns/0/purchase'); sampler.setMethod('POST')
sampler.setPostBodyRaw(true); sampler.addNonEncodedArgument('', '{"clientKey":"transport-bootstrap"}', '')
sampler.setConnectTimeout(props.getProperty('timeoutMs','30000'))
sampler.setResponseTimeout(props.getProperty('timeoutMs','30000'))
def headers = new HeaderManager(); headers.add(new Header('Content-Type','application/json'))
sampler.setHeaderManager(headers)
def result = sampler.sample()
def previous = ctx.getPreviousResult()
def businessCode = ''
try {
    ctx.setPreviousResult(result)
    // Use EXACT main-plan script text/cache keys: the first compilation cannot
    // serialize thousands of post-processors while other buyers time HTTP calls.
    def factory = DocumentBuilderFactory.newInstance()
    factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    def plan = factory.newDocumentBuilder().parse(new File('infra/jmeter/commerce-sale.jmx'))
    def nodes = plan.getElementsByTagName('JSR223PostProcessor')
    for (int i=0; i<nodes.length; i++) {
        def node=nodes.item(i)
        if (!(node.getAttribute('testname') in ['release-cold-purchase-permit','capture-business-code'])) continue
        def processor=new JSR223PostProcessor()
        processor.setScriptLanguage('groovy'); processor.setProperty('cacheKey','true')
        def properties=node.getElementsByTagName('stringProp')
        for (int j=0; j<properties.length; j++)
            if (properties.item(j).getAttribute('name')=='script') processor.setScript(properties.item(j).textContent)
        processor.process()
    }
    businessCode=vars.get('businessCode')
} finally { ctx.setPreviousResult(previous) }
boolean passed=result.responseCode=='401' && businessCode=='UNAUTHENTICATED'
def evidence=[passed:passed,method:'POST',path:'/api/sale-campaigns/0/purchase',
    credentialsSent:false,expectedStatus:401,actualStatus:result.responseCode,
    businessCode:businessCode,elapsedMs:result.time,completedAtMs:System.currentTimeMillis(),
    formalSample:false,successfulPurchases:0]
def location=new File(props.getProperty('barrierStatsPath')).parentFile
new File(location,'transport-bootstrap.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(evidence)),'UTF-8')
if (!passed) { ctx.engine.stopTest(true); throw new IllegalStateException('Transport initialization negative-auth probe failed') }
props.put('transportBootstrapPassed',Boolean.TRUE)
