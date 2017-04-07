package com.github.sybila;

/**
 *     Java Program to Implement Tarjan Algorithm
 **/

import java.util.*;

/** class Tarjan **/
class Tarjan
{
    /** number of vertices **/
    private int V;
    /** preorder number counter **/
    private int preCount;
    /** low number of v **/
    private int[] low;
    /** to check if v is visited **/
    private boolean[] visited;
    /** to store given graph **/
    private List<Integer>[] graph;
    /** to store all scc **/
    private int sccComp;
    private Stack<Integer> stack = new Stack<>();

    /** function to get all strongly connected components **/
    public int getSCComponents(List<Integer>[] graph)
    {
        V = graph.length;
        this.graph = graph;
        low = new int[V];
        visited = new boolean[V];
        stack.clear();
        //stack = new Stack<>();
        sccComp = 0;

        for (int v = 0; v < V; v++)
            if (!visited[v])
                dfs(v);

        return sccComp;
    }

    /** function dfs **/
    private void dfs(int v)
    {
        low[v] = preCount++;
        visited[v] = true;
        stack.push(v);
        int min = low[v];
        for (int w : graph[v])
        {
            if (!visited[w])
                dfs(w);
            if (low[w] < min)
                min = low[w];
        }
        if (min < low[v])
        {
            low[v] = min;
            return;
        }
        //List<Integer> component = new ArrayList<>();
        int w;
        do
        {
            w = stack.pop();
            //component.add(w);
            low[w] = V;
        } while (w != v);
        sccComp += 1;
        //sccComp.add(component);
    }

}