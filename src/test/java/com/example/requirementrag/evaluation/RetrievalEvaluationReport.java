package com.example.requirementrag.evaluation;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Reproducible JSON and Markdown report generated only under target/. */
public record RetrievalEvaluationReport(String dataset,String generatedAt,int cutoff,String mode,String classification,
                                        int warmupRuns,int repetitions,int datasetCaseCount,Summary summary,
                                        Map<String,Summary> profiles,List<RetrievalEvaluationMatcher.CaseResult> cases) {
    public static RetrievalEvaluationReport create(String dataset,List<RetrievalEvaluationMatcher.CaseResult> cases) {
        return create(dataset,"calibration",0,1,cases);
    }
    public static RetrievalEvaluationReport create(String dataset,String mode,int warmups,int repetitions,
                                                   List<RetrievalEvaluationMatcher.CaseResult> cases) {
        Map<String,Summary> perProfile = cases.stream().collect(Collectors.groupingBy(
                c -> c.profile().name(), TreeMap::new, Collectors.collectingAndThen(Collectors.toList(), RetrievalEvaluationReport::summarize)));
        int unique=(int)cases.stream().map(RetrievalEvaluationMatcher.CaseResult::id).distinct().count();
        String classification = unique >= 50 && repetitions > 0 ? "formal" : "calibration";
        return new RetrievalEvaluationReport(dataset,Instant.now().toString(),RetrievalEvaluationMatcher.DEFAULT_CUTOFF,
                mode,classification,warmups,repetitions,unique,summarize(cases),Map.copyOf(perProfile),List.copyOf(cases));
    }
    public void write(Path dir,ObjectMapper mapper) throws IOException {
        Files.createDirectories(dir); mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("report.json").toFile(),this);
        Files.writeString(dir.resolve("report.md"),markdown(), StandardCharsets.UTF_8);
    }
    String markdown() {
        StringBuilder t=new StringBuilder("# Retrieval Evaluation Report\n\n");
        t.append("- Mode: `").append(mode).append("`\n- Classification: `").append(classification)
                .append("`\n- Dataset: `").append(dataset).append("`\n- Dataset cases: ").append(datasetCaseCount)
                .append("\n- Warm-up runs: ").append(warmupRuns).append("\n- Repetitions: ").append(repetitions).append("\n\n");
        appendSummary(t,"Overall",summary);
        profiles.forEach((name,value)->appendSummary(t,name,value));
        t.append("## Cases\n\n| ID | Rep | Profile | Success | Doc rank | Code rank | Latency ms | Warnings |\n|---|---:|---|---:|---:|---:|---:|---|\n");
        for(var r:cases)t.append('|').append(escape(r.id())).append('|').append(r.repetition()).append('|').append(r.profile())
                .append('|').append(r.success()?"PASS":"FAIL").append('|').append(rank(r.documentRank())).append('|')
                .append(rank(r.codeRank())).append('|').append(r.totalLatencyMs()).append('|')
                .append(escape(r.warnings().stream().map(w->w.code()).distinct().collect(Collectors.joining(",")))).append("|\n");
        return t.toString();
    }
    private static void appendSummary(StringBuilder t,String title,Summary s){
        t.append("## ").append(title).append("\n\n| Metric | Value | Raw |\n|---|---:|---:|\n");
        metric(t,"Document Recall@10",s.documentRecallAt10(),s.documentHits(),s.documentCases());
        metric(t,"Code Recall@10",s.codeRecallAt10(),s.codeHits(),s.codeCases());
        t.append("|MRR@10|").append(String.format(Locale.ROOT,"%.3f",s.mrrAt10())).append("|")
                .append(String.format(Locale.ROOT,"%.3f",s.reciprocalRankSum())).append('/').append(s.reciprocalRankItems()).append("|\n");
        t.append("\nP50 ").append(s.p50LatencyMs()).append(" ms; P95 ").append(s.p95LatencyMs())
                .append(" ms; BGE attempts/success/degradation/no-candidate skips ")
                .append(s.bgeCalls()).append('/').append(s.bgeSuccesses()).append('/')
                .append(s.bgeDegradations()).append('/').append(s.bgeNoCandidateSkips()).append(".\n\n");
    }
    private static Summary summarize(List<RetrievalEvaluationMatcher.CaseResult> cases){
        int dc=0,dh=0,cc=0,ch=0,mc=0,mh=0,nc=0,nh=0,failed=0,infra=0,rrn=0,bcall=0,bs=0,bd=0,bskip=0; double rrs=0;
        for(var r:cases){if(r.expectsDocuments()){dc++;rrn++;if(r.documentRank()!=null){dh++;rrs+=1d/r.documentRank();}}
            if(r.expectsCode()){cc++;rrn++;if(r.codeRank()!=null){ch++;rrs+=1d/r.codeRank();}}
            if(r.expectsDocuments()&&r.expectsCode()){mc++;if(r.documentRank()!=null&&r.codeRank()!=null)mh++;}
            if(r.expectedOutcome()==RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS){nc++;if(r.success())nh++;}
            if(!r.success())failed++; if(isInfrastructureFailure(r))infra++;
            bcall+=r.bgeCalls();bs+=r.bgeSuccesses();bd+=r.bgeDegradations();bskip+=r.bgeNoCandidateSkips();}
        List<Long> l=cases.stream().map(RetrievalEvaluationMatcher.CaseResult::totalLatencyMs).sorted().toList();
        return new Summary(cases.size(),failed,infra,dc,dh,rate(dh,dc),cc,ch,rate(ch,cc),rrn,rrs,rate(rrs,rrn),mc,mh,rate(mh,mc),nc,nh,rate(nh,nc),pct(l,.5),pct(l,.95),bcall,bs,bd,bskip);
    }
    private static boolean isInfrastructureFailure(RetrievalEvaluationMatcher.CaseResult result) {
        return result.documentError()!=null || result.codeError()!=null
                || result.warnings().stream().anyMatch(warning -> warning.code()!=null
                && warning.code().endsWith("_UNAVAILABLE"));
    }
    private static double rate(double n,int d){return d==0?0:n/d;} private static long pct(List<Long>s,double q){return s.isEmpty()?0:s.get(Math.max(0,(int)Math.ceil(q*s.size())-1));}
    private static void metric(StringBuilder t,String n,double v,int a,int b){t.append('|').append(n).append('|').append(String.format(Locale.ROOT,"%.3f",v)).append('|').append(a).append('/').append(b).append("|\n");}
    private static String rank(Integer r){return r==null?"-":r.toString();} private static String escape(String v){return v==null?"":v.replace("|","\\|");}
    public record Summary(int totalCases,int failedCases,int infrastructureFailureCases,int documentCases,int documentHits,double documentRecallAt10,
                          int codeCases,int codeHits,double codeRecallAt10,int reciprocalRankItems,double reciprocalRankSum,double mrrAt10,
                          int mixedCases,int mixedBothHits,double mixedBothHitRate,int noResultCases,int noResultHits,double noResultAccuracy,
                          long p50LatencyMs,long p95LatencyMs,int bgeCalls,int bgeSuccesses,int bgeDegradations,
                          int bgeNoCandidateSkips){}
}
